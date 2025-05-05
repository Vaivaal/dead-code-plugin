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
import org.gradle.api.Plugin
import org.gradle.api.Project
import groovy.json.JsonOutput


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

                def toMethodKey = { typeName, methodName ->
                    return "${typeName}.${methodName}".replace("\$", ".")
                }

                Set<String> controllerMethods = [] as Set

                javaSourceDirs.each { sourceDir ->
                    sourceDir.eachFileRecurse { file ->
                        if (file.name.endsWith(".java")) {
                            def cuOpt = parser.parse(file).getResult()
                            if (cuOpt.isPresent()) {
                                CompilationUnit cu = cuOpt.get()

                                cu.findAll(MethodDeclaration).each { m ->
                                    String methodFqn
                                    try {
                                        def resolved = m.resolve()
                                        methodFqn = toMethodKey(resolved.declaringType.qualifiedName, resolved.name)
                                    } catch (Exception e) {
                                        def className = m.findAncestor(ClassOrInterfaceDeclaration)
                                                .map {
                                                    def pkg = cu.packageDeclaration.map { it.nameAsString }.orElse("")
                                                    return pkg ? "${pkg}.${it.nameAsString}" : it.nameAsString
                                                }.orElse("<unknown>")
                                        methodFqn = toMethodKey(className, m.nameAsString)
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

                                    if (m.nameAsString == "main" && m.isStatic()) {
                                        entryPoints << methodFqn
                                    }

                                    m.walk(MethodCallExpr) { call ->
                                        try {
                                            def resolved = call.resolve()
                                            def declaringType

                                            if (resolved.metaClass.hasProperty(resolved, 'declaringType')) {
                                                declaringType = resolved.declaringType.qualifiedName
                                            } else if (resolved.declaringType() != null) {
                                                declaringType = resolved.declaringType().qualifiedName
                                            } else {
                                                declaringType = "<unknown>"
                                            }

                                            def projectGroup = project.group.toString()
                                            if (!declaringType.startsWith(projectGroup)) {
                                                return
                                            }

                                            def calledMethod = "${declaringType}.${resolved.name}"
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

                                                    def fallbackCalled = "${declaringType}.${call.nameAsString}"
                                                    callGraph[methodFqn] << fallbackCalled
                                                    //println "Fallback (scope-resolved): $fallbackCalled from $methodFqn"
                                                    return
                                                }
                                            } catch (Exception ignored) {}

                                            def fallbackClass = m.findAncestor(ClassOrInterfaceDeclaration)
                                                    .map { classDecl ->
                                                        def pkg = cu.packageDeclaration.map { it.nameAsString }.orElse("")
                                                        return pkg ? "${pkg}.${classDecl.nameAsString}" : classDecl.nameAsString
                                                    }.orElse("<unknown>")
                                            def fallbackCalled = "${fallbackClass}.${call.nameAsString}"
                                            callGraph[methodFqn] << fallbackCalled
                                            //println "Fallback (guessed class): $fallbackCalled from $methodFqn"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                entryPoints.addAll(callGraph.keySet())

//                callGraph.each { caller, callees ->
//                    println "${caller} calls:"
//                    callees.each { callee ->
//                        println "    -> $callee"
//                    }
//                }

                callGraph.keySet().each { entryPoints << it }

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
                                    declaringType = resolved.declaringType.qualifiedName
                                } else if (resolved.declaringType() != null) {
                                    declaringType = resolved.declaringType().qualifiedName
                                }
                            } catch (Exception ignored) {}

                            if (declaringType.startsWith(libPrefix)) {
                                usedLibraryMethods << "${declaringType}.${resolved.name}"
                            }
                        } catch (Exception e) {
                            if (call.toString().contains(libPrefix)) {
                                //unresolvedCalls << "Could not resolve: ${call} in ${file.name} – ${e.message}"
                            }
                        }
                    }

                    cu.findAll(MethodReferenceExpr).each { ref ->
                        try {
                            def resolved = ref.resolve()
                            def type = "<unknown>"
                            try {
                                type = resolved.declaringType?.qualifiedName ?: "<unknown>"
                            } catch (Exception ignored) {}

                            if (type.startsWith(libPrefix)) {
                                usedLibraryMethods << "${type}.${resolved.name}"
                            }
                        } catch (Exception e) {
                            //unresolvedCalls << "Could not resolve method reference: ${ref} in ${file.name} – ${e.message}"
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
    }
}