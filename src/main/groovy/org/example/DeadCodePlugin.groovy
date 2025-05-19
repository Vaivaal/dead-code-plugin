package org.example

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.InitializerDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.javaparsermodel.contexts.ObjectCreationContext
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import com.github.javaparser.ast.body.EnumDeclaration
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import groovy.json.JsonOutput
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.body.ConstructorDeclaration
import org.gradle.api.JavaVersion


class DeadCodePlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {

        project.task('findPossiblyUnusedMethods') {
            group = 'analysis'

            doLast{
                project.configurations.maybeCreate('lombok')
                project.dependencies.add('lombok', 'org.projectlombok:lombok:1.18.30')

                def javaSourceDirs = project.sourceSets.main.allJava.srcDirs.findAll { it.exists() }

                if (javaSourceDirs.isEmpty()) {
                    println "No Java source directories found in this project."
                    return
                }

                def delombokDir = new File("${project.buildDir}/delombok")
                delombokDir.mkdirs()

                project.javaexec {
                    mainClass = 'lombok.launch.Main'
                    classpath = project.configurations.lombok + project.sourceSets.main.compileClasspath
                    args = ['delombok', 'src/main/java', '-d', delombokDir.absolutePath]
                }

                def typeSolver = new CombinedTypeSolver()
                typeSolver.add(new ReflectionTypeSolver())
                typeSolver.add(new JavaParserTypeSolver(delombokDir))
                javaSourceDirs.each { dir ->
                    typeSolver.add(new JavaParserTypeSolver(dir))
                }

                def runtimeConfig = project.configurations.findByName("runtimeClasspath")
                if (runtimeConfig != null && runtimeConfig.canBeResolved) {
                    runtimeConfig.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                        if (artifact.type == 'jar') {
                            try {
                                typeSolver.add(new JarTypeSolver(artifact.file))
                            } catch (Exception e) {
                                println "Failed to add JAR: ${artifact.file.name} - ${e.message}"
                            }
                        }
                    }
                }

//                def parser = new JavaParser(new ParserConfiguration()
//                        .setSymbolResolver(new JavaSymbolSolver(typeSolver))
//                )
                def javaVersion = JavaVersion.current()

                ParserConfiguration config = new ParserConfiguration()
                        .setLanguageLevel(ParserConfiguration.LanguageLevel.valueOf("JAVA_${javaVersion.majorVersion}"))
                        .setSymbolResolver(new JavaSymbolSolver(typeSolver));
                JavaParser parser = new JavaParser(config);

                Map<String, Set<String>> callGraph = [:].withDefault { [] as Set }
                Set<String> instantiatedTypes = [] as Set
                Set<String> entryPoints = [] as Set
                Set<String> allMethods = [] as Set
                Map<String, String> fieldTypes = [:]

                javaSourceDirs.each{ sourceDir ->
                    //println sourceDir
                    sourceDir.eachFileRecurse {file ->
                        if (file.name.endsWith('.java')){

                            //println "Found java file: ${file.absolutePath}"

                            def cuOpt = parser.parse(file).getResult()
                            if (cuOpt.isPresent()) {
                                //println "present"
                                CompilationUnit cu = cuOpt.get()

                                cu.findAll(FieldDeclaration).each {field ->
                                    field.variables.each {var ->
                                        try{
                                            def fieldType = field.commonType.resolve().describe()
                                            fieldTypes[var.nameAsString] = fieldType

                                        }catch(Exception e){
                                            println e
                                        }

                                        if (var.initializer.isPresent()){
                                            def initExpr = var.initializer.get()

                                            initExpr.walk (MethodCallExpr) {call ->
                                                try{
                                                    def resolved = call.resolve()
                                                    def declaringType = resolved.declaringType().qualifiedName
                                                    def paramTypesCall = (0..<resolved.numberOfParams).collect { param ->
                                                        try {
                                                            resolved.getParam(param).type.describe()
                                                        } catch (Exception e) {
                                                            "<?>"
                                                        }
                                                    }
                                                    def calledMethod = toMethodKey(declaringType, resolved.name, paramTypesCall)
                                                    callGraph["<fieldInit>"] << calledMethod

                                                }catch (Exception ex){
                                                    println ex
                                                    //TO DO: dependency injection
                                                }
                                            }
                                        }
                                    }
                                }

                                cu.findAll(ObjectCreationExpr).each {objectCreation ->
                                    try {
                                        def type = objectCreation.calculateResolvedType();
                                        //println type
                                        if (type.isReferenceType()){
                                            instantiatedTypes << type.asReferenceType().qualifiedName
                                        }
                                    }catch (Exception e){
                                        //println e
                                    }
                                }

                                cu.findAll(ConstructorDeclaration).each { ctor ->
                                    try {
                                        ctor.walk(MethodCallExpr){call ->
                                            try{
                                                def resolvedCall = call.resolve()
                                                def declaringType = resolvedCall.declaringType().qualifiedName
                                                def paramTypesCall = (0..<resolvedCall.numberOfParams).collect { param ->
                                                    try {
                                                        resolvedCall.getParam(param).type.describe()
                                                    } catch (Exception e) {
                                                        "<?>"
                                                    }
                                                }
                                                def projectGroup = project.group.toString()
                                                if(!declaringType.startsWith(projectGroup)){
                                                    //println "${declaringType} Does not start with ${projectGroup}"
                                                    return
                                                }

                                                def calledMethod = toMethodKey(declaringType, resolvedCall.name, paramTypesCall)
                                                callGraph["<constructor>"] << calledMethod

                                            }catch(Exception e){
                                                //println e
                                            }
                                        }
                                    }catch(Exception e){
                                        //println e
                                    }
                                }

                                cu.findAll(MethodDeclaration).each{ m ->
                                    List<String> paramTypes = []
                                    String methodFqn
                                    try{
                                        def resolved = m.resolve()
//                                        if (!resolved.declaringType().qualifiedName.startsWith(project.group.toString())){
//                                            return
//                                        }
                                        paramTypes = (0..<resolved.numberOfParams).collect { param ->
                                            try {
                                                resolved.getParam(param).type.describe()
                                            } catch (Exception e) {
                                                "<?>"
                                            }
                                        }
                                        methodFqn = toMethodKey(resolved.declaringType().qualifiedName, resolved.name, paramTypes)
                                        //println "found method declaration: ${methodFqn}"
                                    }catch(Exception e){
                                        //println e
                                        try{
                                            def parentType = m.findAncestor(ClassOrInterfaceDeclaration).orElse(null)
                                            def className = parentType?.nameAsString ?: "<unknown>"
                                            def packageDecl = cu.packageDeclaration.isPresent() ? cu.packageDeclaration.get().nameAsString : ""
                                            def fullType = packageDecl ? "$packageDecl.$className" : className

                                            paramTypes = m.parameters.collect { param ->
                                                try{
                                                    param.type.resolve().describe()
                                                }catch (Exception ex){
                                                    println ex
                                                    "<?>"
                                                }
                                            }

                                            methodFqn = toMethodKey(fullType, m.nameAsString, paramTypes)
                                            //println "found method declaration: ${methodFqn}"
                                        }catch(Exception exe){
                                            println exe
                                        }
                                    }
                                    //println methodFqn
                                    if (methodFqn == null){
                                        //println m
                                    }

                                    def annotationsToIgnore = ['PostConstruct', 'PreDestroy', 'Scheduled', 'EventListener', 'Bean']

                                    boolean isAnnotated = m.annotations.any{ ann ->
                                        def annName = ann.name.asString()
                                        annotationsToIgnore.contains(annName)
                                    }

                                    if (isAnnotated){
                                        entryPoints << methodFqn
                                    }

                                    allMethods << methodFqn

                                    m.walk(MethodCallExpr) {call ->
                                        try {
                                            def resolved = call.resolve()
                                            def declaringType = resolved.declaringType().qualifiedName
                                            def paramTypesCall = (0..<resolved.numberOfParams).collect { param ->
                                                try {
                                                    resolved.getParam(param).type.describe()
                                                } catch (Exception e) {
                                                    "<?>"
                                                }
                                            }

                                            def calledMethod = toMethodKey(declaringType, resolved.name, paramTypesCall)
//                                            println "Caller ${methodFqn}"
//                                            println "Calleeeeeee ${calledMethod}"
                                            callGraph[methodFqn] << calledMethod

                                        }catch(Exception e){
                                            //println e
                                            if (call.scope.isPresent()){
                                                def scope = call.scope.get()
                                                if (scope.isNameExpr()){
                                                    def callerVar = scope.asNameExpr().getNameAsString()
                                                    def fieldType = fieldTypes[callerVar]
                                                    if (fieldType){

                                                        def paramTypesDI = call.arguments.collect {arg ->
                                                            try{
                                                                arg.calculateResolvedType().describe()
                                                            }catch (Exception ex){
                                                                "<?>"
                                                            }
                                                        }

                                                        def calledMethod = toMethodKey(fieldType, call.nameAsString, paramTypesDI)
//                                                        println "Caller ${methodFqn}"
//                                                        println "Calleeeeeee ${calledMethod}"

                                                        callGraph[methodFqn] << calledMethod
                                                    }
                                                }
                                            }
                                        }

                                    }

                                    m.walk(MethodReferenceExpr) { ref ->
                                        try {
                                            def resolvedRef = ref.resolve()
                                            def declaringType = resolvedRef.declaringType().qualifiedName
                                            def paramTypesCall = (0..<resolvedRef.numberOfParams).collect { i ->
                                                try {
                                                    resolvedRef.getParam(i).type.describe()
                                                } catch (Exception e) {
                                                    "<?>"
                                                }
                                            }
                                            def calledMethod = toMethodKey(declaringType, resolvedRef.name, paramTypesCall)

//                                            println "Caller ${methodFqn}"
//                                            println "Calleeeeeee ${calledMethod}"

                                            callGraph[methodFqn] << calledMethod
                                        } catch (Exception e) {
                                            println "Could not resolve method reference: ${ref} — ${e.message}"
                                        }
                                    }

                                }

                                cu.findAll(InitializerDeclaration).each {initializer ->
                                    String methodFqn = "<initializer>"

                                    //allMethods << methodFqn

                                    initializer.walk(MethodCallExpr) {call ->
                                        try {
                                            def resolved = call.resolve()
                                            def declaringType = resolved.declaringType().qualifiedName
                                            def paramTypesCall = (0..<resolved.numberOfParams).collect { i ->
                                                try {
                                                    resolved.getParam(i).type.describe()
                                                } catch (Exception e) {
                                                    "<?>"
                                                }
                                            }
                                            def calledMethod = toMethodKey(declaringType, resolved.name, paramTypesCall)
                                            callGraph[methodFqn] << calledMethod
                                        }catch (Exception ex){
                                            println ex
                                            //TO DO: add dependency injection
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

//                callGraph.each { caller, callees ->
//                    println "$caller calls:"
//                    callees.each { callee ->
//                        println "  -> $callee"
//                    }
//                }

                callGraph.values().each { callees ->
                    callees.each { callee -> entryPoints.add(callee) }
                }

                allMethods.each { methodKey ->
                    if (methodKey.endsWith(".main(java.lang.String[])") || methodKey.endsWith(".main(java.lang.String...)")) {
                        entryPoints << methodKey
                    }
                }

//                Set<String> reachable = [] as Set
//                def queue = new LinkedList<String>(entryPoints)
//
//                while (!queue.isEmpty()) {
//                    def current = queue.poll()
//                    if (reachable.contains(current)) continue
//
//                    reachable << current
//                    callGraph[current]?.each { callee ->
//                        if (!reachable.contains(callee)) {
//                            queue << callee
//                        }
//                    }
//                }

                def unusedMethods = (allMethods - entryPoints).toSorted()
                def outputFile = new File("${project.buildDir}/potentially-unused-methods.json")
                outputFile.parentFile.mkdirs()
                outputFile.text = JsonOutput.prettyPrint(JsonOutput.toJson([
                        potentiallyUnusedMethods: unusedMethods
                ]))

                println "\nUnused methods written to: ${outputFile}"
            }
        }

        project.task('findUsedLibraryMethods') {
            group = 'analysis'

            doLast {
                def libPrefixesRaw = project.findProperty("libraryPackages") ?: ""
                if (!libPrefixesRaw) {
                    println "Missing required parameter: -P libraryPackages=com.example.lib,org.example.util"
                    return
                }
                def libPrefixes = libPrefixesRaw.split(',').collect { it.trim() }

                //println "Scanning for used methods from library: $libPrefix"

                def findOverriddenLibraryMethod = { resolved, libPrefix ->
                    try {
                        def declaringType = resolved.declaringType()
                        def paramCount = resolved.numberOfParams
                        def paramTypes = (0..<paramCount).collect { i ->
                            try {
                                resolved.getParam(i).type.describe()
                            } catch (Exception e) {
                                "<?>"
                            }
                        }

                        def ancestors = declaringType.getAllAncestors()

                        for (ancestor in ancestors) {
                            if (!libPrefix.any { prefix -> ancestor.qualifiedName.startsWith(prefix) }) continue

                            def ancestorOpt = ancestor.getTypeDeclaration()
                            if (!ancestorOpt.isPresent()) continue

                            def ancestorDecl = ancestorOpt.get()

                            def match = ancestorDecl.getDeclaredMethods().find { m ->
                                m.name == resolved.name &&
                                        m.numberOfParams == paramCount &&
                                        (0..<paramCount).every { i ->
                                            try {
                                                m.getParam(i).type.describe() == paramTypes[i]
                                            } catch (Exception e) {
                                                false
                                            }
                                        }
                            }

                            if (match != null) return match
                        }
                    } catch (Exception e) {
                        println "findOverriddenLibraryMethod failed: ${e.message}"
                    }
                    return null
                }

                project.configurations.maybeCreate('lombok')
                project.dependencies.add('lombok', 'org.projectlombok:lombok:1.18.30')

                def javaSourceDirs = project.sourceSets.main.allJava.srcDirs.findAll { it.exists() }

                if (javaSourceDirs.isEmpty()) {
                    println "No Java source directories found in this project."
                    return
                }

                def delombokDir = new File("${project.buildDir}/delombok")
                delombokDir.mkdirs()

                project.javaexec {
                    mainClass = 'lombok.launch.Main'
                    classpath = project.configurations.lombok + project.sourceSets.main.compileClasspath
                    args = ['delombok', 'src/main/java', '-d', delombokDir.absolutePath]
                }

                def typeSolver = new CombinedTypeSolver()
                typeSolver.add(new ReflectionTypeSolver())
                typeSolver.add(new JavaParserTypeSolver(delombokDir))

                javaSourceDirs.each { dir -> typeSolver.add(new JavaParserTypeSolver(dir)) }

                def runtimeConfig = project.configurations.findByName("runtimeClasspath")
                if (runtimeConfig != null && runtimeConfig.canBeResolved) {
                    runtimeConfig.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                        if (artifact.type == 'jar') {
                            try {
                                typeSolver.add(new JarTypeSolver(artifact.file))
                            } catch (Exception e) {
                                println "Failed to add JAR: ${artifact.file.name} - ${e.message}"
                            }
                        }
                    }
                }

                def javaVersion = JavaVersion.current()
                def config = new ParserConfiguration()
                        .setLanguageLevel(ParserConfiguration.LanguageLevel.valueOf("JAVA_${javaVersion.majorVersion}"))
                        .setSymbolResolver(new JavaSymbolSolver(typeSolver))
                def parser = new JavaParser(config)

                Set<String> usedLibraryMethods = [] as Set
                //Set<String> unresolvedCalls = [] as Set

                javaSourceDirs.each { sourceDir ->
                    sourceDir.eachFileRecurse {file ->
                        if (!file.name.endsWith('.java')) return

                        def cuOpt = parser.parse(file).getResult()
                        if (!cuOpt.isPresent()) return

                        def cu = cuOpt.get()

                        cu.findAll(MethodCallExpr).each {call ->
                            try{
                                def resolved = call.resolve()
                                def declaringType = resolved.declaringType()
                                def declaringFqn = declaringType.qualifiedName

                                def paramTypes = (0..<resolved.numberOfParams).collect { i ->
                                    try {
                                        resolved.getParam(i).type.describe()
                                    } catch (Exception e) {
                                        "<?>"
                                    }
                                }

                                if (libPrefixes.any { prefix -> declaringFqn.startsWith(prefix) }) {
                                    // Tiesiogiai iš bibliotekos
                                    def methodKey = toMethodKey(declaringFqn, resolved.name, paramTypes)
                                    usedLibraryMethods << methodKey
                                } else {
                                    // Tikrinam, ar override’ina bibliotekos metodą
                                    def overridden = findOverriddenLibraryMethod(resolved, libPrefixes)
                                    if (overridden != null) {
                                        def overriddenFqn = overridden.declaringType().qualifiedName
                                        def overriddenParamTypes = (0..<overridden.numberOfParams).collect { i ->
                                            try {
                                                overridden.getParam(i).type.describe()
                                            } catch (Exception e) {
                                                "<?>"
                                            }
                                        }
                                        def methodKey = toMethodKey(overriddenFqn, overridden.name, overriddenParamTypes)
                                        usedLibraryMethods << methodKey
                                    }
                                }
                            }catch(Exception e){
                                println e
                                if (call.scope.isPresent()) {
                                    def scopeExpr = call.scope.get()
                                    try {
                                        def scopeType = scopeExpr.calculateResolvedType()
                                        if (scopeType.isReferenceType()) {
                                            def qualifiedType = scopeType.asReferenceType().qualifiedName
                                            if (libPrefixes.any { prefix -> qualifiedType.startsWith(prefix) }) {
                                                def paramTypesDI = call.arguments.collect { arg ->
                                                    try {
                                                        arg.calculateResolvedType().describe()
                                                    } catch (Exception ex) {
                                                        "<?>"
                                                    }
                                                }
                                                def methodKey = toMethodKey(qualifiedType, call.nameAsString, paramTypesDI)
                                                usedLibraryMethods << methodKey
                                            }
                                        }
                                    } catch (Exception ignored) {
                                        println ignored
                                    }
                                }
                            }
                        }

                        cu.findAll(MethodReferenceExpr).each {ref ->
                            try{
                                def resolved = ref.resolve()
                                def declaringType = resolved.declaringType()
                                def declaringFqn = declaringType.qualifiedName

                                def paramTypes = (0..<resolved.numberOfParams).collect { i ->
                                    try {
                                        resolved.getParam(i).type.describe()
                                    } catch (Exception e) {
                                        "<?>"
                                    }
                                }

                                if (libPrefixes.any { prefix -> declaringFqn.startsWith(prefix) }) {
                                    // Tiesiogiai iš bibliotekos
                                    def methodKey = toMethodKey(declaringFqn, resolved.name, paramTypes)
                                    usedLibraryMethods << methodKey
                                } else {
                                    // Tikrinam, ar override’ina bibliotekos metodą
                                    def overridden = findOverriddenLibraryMethod(resolved, libPrefixes)
                                    if (overridden != null) {
                                        def overriddenFqn = overridden.declaringType().qualifiedName
                                        def overriddenParamTypes = (0..<overridden.numberOfParams).collect { i ->
                                            try {
                                                overridden.getParam(i).type.describe()
                                            } catch (Exception e) {
                                                "<?>"
                                            }
                                        }
                                        def methodKey = toMethodKey(overriddenFqn, overridden.name, overriddenParamTypes)
                                        usedLibraryMethods << methodKey
                                    }
                                }
                            }catch(Exception ex){
                                println ex
                            }
                        }
                    }
                }

                def outputFile = new File("${project.buildDir}/used-library-methods.json")
                outputFile.parentFile.mkdirs()
                outputFile.text = JsonOutput.prettyPrint(JsonOutput.toJson([
                        usedMethods: usedLibraryMethods.sort()
                ]))

                println "\nOutput written to ${outputFile.absolutePath}"
            }
        }

        project.task('findTrulyUnusedMethods') {
            group = 'analysis'

            doLast {
                def unusedFilePath = project.hasProperty('unusedMethodsFile') ? project.property('unusedMethodsFile') : null
                def usedDirPath = project.hasProperty('usedMethodsDir') ? project.property('usedMethodsDir') : null

                if (!unusedFilePath || !usedDirPath) {
                    throw new GradleException("Usage: -P unusedMethodsFile=<path> -P usedMethodsDir=<path>")
                }

                def unusedFile = project.file(unusedFilePath)
                def usedDir = project.file(usedDirPath)

                if (!unusedFile.exists()) {
                    throw new GradleException("Unused methods file not found: $unusedFilePath")
                }
                if (!usedDir.exists() || !usedDir.directory) {
                    throw new GradleException("Used methods directory not found or not a directory: $usedDirPath")
                }

                def jsonSlurper = new JsonSlurper()
                def unusedJson = jsonSlurper.parse(unusedFile)
                def unusedMethods = unusedJson.unusedMethods as Set

                def usedMethods = [] as Set
                usedDir.eachFileMatch(~/.*\.json/) { file ->
                    def parsed = jsonSlurper.parse(file)
                    def methods = parsed.usedMethods
                    if (methods instanceof List) {
                        usedMethods.addAll(methods)
                    }
                }

                def trulyUnused = unusedMethods.findAll { !usedMethods.contains(it) }

                def outputFile = new File(project.buildDir, "unused-methods.json")
                def outputObject = [unusedMethods: trulyUnused.sort()]
                outputFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(outputObject))

                println "Saved to: ${outputFile.absolutePath}"
            }
        }
    }

    String toMethodKey(String declaringClass, String methodName, List<String> paramTypes){
        return "${declaringClass}.${methodName}(${paramTypes.join(',')})"
    }
}