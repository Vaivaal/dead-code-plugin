package org.example

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.javaparser.symbolsolver.JavaSymbolSolver
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


class DeadCodePlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {

        project.task('findPossiblyUnusedMethods') {
            group = 'analysis'

            doLast {
                def javaSourceDirs = project.sourceSets.main.allJava.srcDirs.findAll { it.exists() }

                if (javaSourceDirs.isEmpty()) {
                    println "No Java source directories found in this project."
                    return
                }

                def typeSolver = new CombinedTypeSolver()
                typeSolver.add(new ReflectionTypeSolver())
                javaSourceDirs.each { dir ->
                    typeSolver.add(new JavaParserTypeSolver(dir))
                }

                def parser = new JavaParser(new ParserConfiguration()
                        .setSymbolResolver(new JavaSymbolSolver(typeSolver))
                )

                Map<String, Set<String>> callGraph = [:].withDefault { [] as Set }
                Set<String> allMethods = [] as Set
                Set<String> entryPoints = [] as Set
                Set<String> instantiatedTypes = [] as Set

                def toMethodKey = { typeName, methodName, paramTypes ->
                    def params = paramTypes.join(',')
                    return "${typeName}.${methodName}(${params})".replace('\$', '.')
                }

                Set<String> controllerMethods = [] as Set

                javaSourceDirs.each { sourceDir ->
                    sourceDir.eachFileRecurse { file ->
                        if (file.name.endsWith(".java")) {
                            def cuOpt = parser.parse(file).getResult()
                            if (cuOpt.isPresent()) {
                                CompilationUnit cu = cuOpt.get()

                                cu.findAll(ObjectCreationExpr).each { objCreation ->
                                    try {
                                        def type = objCreation.calculateResolvedType()
                                        if (type.isReferenceType()) {
                                            instantiatedTypes << type.asReferenceType().qualifiedName
                                        }
                                    } catch (Exception ignored) {}
                                }

                                cu.findAll(ConstructorDeclaration).each { ctor ->
                                    try {
                                        ctor.walk(MethodCallExpr) { call ->
                                            try {
                                                def resolvedCall = call.resolve()
                                                def declaringType = resolvedCall.declaringType().qualifiedName
                                                def paramTypesCall = (0..<resolvedCall.numberOfParams).collect { i ->
                                                    resolvedCall.getParam(i).type.describe().replaceAll('\\s', '')
                                                }

                                                def projectGroup = project.group.toString()
                                                if (!declaringType.startsWith(projectGroup)) {
                                                    return
                                                }

                                                def calledMethod = toMethodKey(declaringType, resolvedCall.name, paramTypesCall)
                                                callGraph["<ctor>"] << calledMethod

                                            } catch (Exception ignored) {}
                                        }
                                    } catch (Exception ignored) {}
                                }

                                cu.findAll(MethodDeclaration).each { m ->
                                    String methodFqn
                                    List<String> paramTypes = []
                                    try {
                                        def resolved = m.resolve()
                                        paramTypes = (0..<resolved.numberOfParams).collect { i ->
                                            resolved.getParam(i).type.describe().replaceAll('\\s', '')
                                        }
                                        methodFqn = toMethodKey(resolved.declaringType().qualifiedName, resolved.name, paramTypes)
                                    } catch (Exception e) {
                                        def classOrEnumNode = m.findAncestor(ClassOrInterfaceDeclaration).orElse(null)
                                        if (!classOrEnumNode) {
                                            classOrEnumNode = m.findAncestor(EnumDeclaration).orElse(null)
                                        }

                                        def className
                                        if (classOrEnumNode != null) {
                                            def pkg = cu.packageDeclaration.map { it.nameAsString }.orElse("")
                                            className = pkg ? "${pkg}.${classOrEnumNode.nameAsString}" : classOrEnumNode.nameAsString
                                        } else {
                                            className = "<unknown>"
                                        }
                                        paramTypes = m.parameters.collect { it.typeAsString }
                                        methodFqn = toMethodKey(className, m.nameAsString, paramTypes)
                                    }

                                    def classNodeOpt = m.findAncestor(ClassOrInterfaceDeclaration)
                                    if (classNodeOpt.isPresent()) {
                                        def classNode = classNodeOpt.get()
                                        def annotationNames = classNode.annotations.collect { it.nameAsString }
                                        if (annotationNames.any { it in ["RestController", "Controller"] }) {
                                            controllerMethods << methodFqn
                                        }
                                    }

                                    allMethods << methodFqn

                                    m.walk(MethodCallExpr) { call ->
                                        try {
                                            def resolved = call.resolve()
                                            def declaringType = resolved.declaringType().qualifiedName
                                            def paramTypesCall = (0..<resolved.numberOfParams).collect { i ->
                                                resolved.getParam(i).type.describe().replaceAll('\\s', '')
                                            }

                                            def projectGroup = project.group.toString()
                                            if (!declaringType.startsWith(projectGroup)) {
                                                return
                                            }

                                            def calledMethod = toMethodKey(declaringType, resolved.name, paramTypesCall)
                                            callGraph[methodFqn] << calledMethod

                                        } catch (Exception e) {
                                            try {
                                                def scope = call.scope.orElse(null)
                                                if (scope != null) {
                                                    def scopeType = scope.calculateResolvedType()
                                                    def declaringType = scopeType.isReferenceType() ? scopeType.asReferenceType().qualifiedName : "<unknown>"

                                                    def projectGroup = project.group.toString()
                                                    if (!declaringType.startsWith(projectGroup)) {
                                                        return
                                                    }

                                                    def fallbackCalled = toMethodKey(declaringType, call.nameAsString, [])
                                                    callGraph[methodFqn] << fallbackCalled
                                                    return
                                                }
                                            } catch (Exception ignored) {}

                                            def fallbackClass = m.findAncestor(ClassOrInterfaceDeclaration)
                                                    .map { classDecl ->
                                                        def pkg = cu.packageDeclaration.map { it.nameAsString }.orElse("")
                                                        return pkg ? "${pkg}.${classDecl.nameAsString}" : classDecl.nameAsString
                                                    }.orElse("<unknown>")
                                            def fallbackCalled = toMethodKey(fallbackClass, call.nameAsString, [])
                                            callGraph[methodFqn] << fallbackCalled
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                allMethods.findAll { it.contains(".main(") }.each { entryPoints << it }
                entryPoints << "<ctor>"

                Set<String> reachable = [] as Set
                def queue = new LinkedList<String>(entryPoints)

                while (!queue.isEmpty()) {
                    def current = queue.poll()
                    if (reachable.contains(current)) continue

                    reachable << current
                    callGraph[current]?.each { callee ->
                        if (!reachable.contains(callee)) {
                            queue << callee
                        }
                    }
                }

                reachable.removeAll(controllerMethods)

                def unusedMethods = (allMethods - reachable).toSorted()
                def outputFile = new File("${project.buildDir}/unused-methods.json")
                outputFile.parentFile.mkdirs()
                outputFile.text = JsonOutput.prettyPrint(JsonOutput.toJson([
                        unusedMethods: unusedMethods
                ]))

                println "\nUnused methods written to: ${outputFile}"
            }
        }

        project.task('findUsedLibraryMethods') {
            group = 'analysis'

            doLast {
                def libPrefix = project.findProperty("libraryPackage") ?: ""
                if (!libPrefix) {
                    println "Missing required parameter: -PlibraryPackage=com.example.lib"
                    return
                }

                println "Scanning for used methods from library: $libPrefix"

                def javaFiles = project.fileTree(project.projectDir).matching {
                    include '**/*.java'
                }.files.findAll { it.exists() }

                if (javaFiles.isEmpty()) {
                    println "No Java source files found in this project."
                    return
                }

                def typeSolver = new CombinedTypeSolver()
                typeSolver.add(new ReflectionTypeSolver())

                project.sourceSets.main.allJava.srcDirs.findAll { it.exists() }.each {
                    typeSolver.add(new JavaParserTypeSolver(it))
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

                def parser = new JavaParser(new ParserConfiguration()
                        .setSymbolResolver(new JavaSymbolSolver(typeSolver))
                )

                Set<String> usedLibraryMethods = [] as Set
                Set<String> unresolvedCalls = [] as Set

                javaFiles.each { file ->
                    def cuOpt = parser.parse(file).getResult()
                    if (!cuOpt.isPresent()) return

                    def cu = cuOpt.get()

                    cu.findAll(MethodCallExpr).each { call ->
                        try {
                            def resolved = call.resolve()
                            def declaringType = "<unknown>"
                            try {
                                if (resolved.metaClass.hasProperty(resolved, "declaringType")) {
                                    declaringType = resolved.declaringType().qualifiedName
                                } else if (resolved.declaringType() != null) {
                                    declaringType = resolved.declaringType().qualifiedName
                                }
                            } catch (Exception ignored) {}

                            if (declaringType.startsWith(libPrefix)) {
                                def paramTypes = (0..<resolved.numberOfParams)
                                        .collect { i ->
                                            try {
                                                return resolved.getParam(i).type.describe()
                                            } catch (Exception e) {
                                                return "<?>"
                                            }
                                        }
                                        .join(", ")
                                usedLibraryMethods << "${declaringType}.${resolved.name}(${paramTypes})"
                            }
                        } catch (Exception e) {
                            println "Exception $e"
                            if (call.toString().contains(libPrefix)) {
                                println "Could not resolve: ${call} in ${file.name} – ${e.message}"
                            }
                        }
                    }

                    cu.findAll(MethodReferenceExpr).each { ref ->
                        try {
                            def resolved = ref.resolve()
                            def type = "<unknown>"
                            try {
                                type = resolved.declaringType()?.qualifiedName ?: "<unknown>"
                            } catch (Exception ignored) {}

                            if (type.startsWith(libPrefix)) {
                                usedLibraryMethods << "${type}.${resolved.name}"
                            }
                        } catch (Exception e) {
                            println "Could not resolve method reference: ${ref} in ${file.name} – ${e.message}"
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

                def outputFile = new File(project.buildDir, "truly-unused-methods.json")
                def outputObject = [trulyUnusedMethods: trulyUnused.sort()]
                outputFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(outputObject))

                println "Saved to: ${outputFile.absolutePath}"
            }
        }
    }
}