import com.beust.jcommander.IStringConverter
import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.beust.klaxon.array
import com.beust.klaxon.lookup
import com.beust.klaxon.obj
import com.beust.klaxon.string

import java.io.File
import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

import kotlin.system.exitProcess

import mu.KotlinLogging

/**
 * A class representing a package manager that handles software dependencies.
 *
 * @property homepageUrl The URL to the package manager's homepage.
 * @property primaryLanguage The name of the programming language this package manager is primarily used with.
 * @property pathsToDefinitionFiles A prioritized list of glob patterns of definition files supported by this package manager.
 *
 */
abstract class PackageManager(
        val homepageUrl: String,
        val primaryLanguage: String,
        val pathsToDefinitionFiles: List<String>
) {
    // Create a recursive glob matcher for all definition files.
    val globForDefinitionFiles = FileSystems.getDefault().getPathMatcher("glob:**/{" + pathsToDefinitionFiles.joinToString(",") + "}")!!

    /**
     * Return the Java class name to make JCommander display a proper name in list parameters of this custom type.
     */
    override fun toString(): String {
        return javaClass.name
    }

    /**
     * Return a tree of resolved dependencies (not necessarily declared dependencies, in case conflicts were resolved)
     * for each provided path.
     */
    abstract fun resolveDependencies(definitionFiles: List<Path>): Map<Path, Dependency>
}

object Bower : PackageManager(
        "https://bower.io/",
        "JavaScript",
        listOf("bower.json")
) {
    override fun resolveDependencies(definitionFiles: List<Path>): Map<Path, Dependency> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

object Bundler : PackageManager(
        "http://bundler.io/",
        "Ruby",
        // See http://yehudakatz.com/2010/12/16/clarifying-the-roles-of-the-gemspec-and-gemfile/.
        listOf("Gemfile.lock", "Gemfile")
) {
    override fun resolveDependencies(definitionFiles: List<Path>): Map<Path, Dependency> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

object CocoaPods : PackageManager(
        "https://cocoapods.org/",
        "Objective-C",
        listOf("Podfile.lock", "Podfile")
) {
    override fun resolveDependencies(definitionFiles: List<Path>): Map<Path, Dependency> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

object Godep : PackageManager(
        "https://godoc.org/github.com/tools/godep",
        "Go",
        listOf("Godeps/Godeps.json")
) {
    override fun resolveDependencies(definitionFiles: List<Path>): Map<Path, Dependency> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

object Gradle : PackageManager(
        "https://gradle.org/",
        "Java",
        listOf("build.gradle")
) {
    override fun resolveDependencies(definitionFiles: List<Path>): Map<Path, Dependency> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

object Maven : PackageManager(
        "https://maven.apache.org/",
        "Java",
        listOf("pom.xml")
) {
    override fun resolveDependencies(definitionFiles: List<Path>): Map<Path, Dependency> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

object NPM : PackageManager(
        "https://www.npmjs.com/",
        "JavaScript",
        listOf("package.json")
) {
    override fun resolveDependencies(definitionFiles: List<Path>): Map<Path, Dependency> {
        val result = mutableMapOf<Path, Dependency>()

        definitionFiles.forEach { definitionFile ->
            val parent = definitionFile.parent.toFile()
            val shrinkwrapLockfile = File(parent, "npm-shrinkwrap.json")
            result[definitionFile] = when {
                File(parent, "yarn.lock").isFile ->
                    resolveYarnDependencies(parent)
                shrinkwrapLockfile.isFile ->
                    resolveShrinkwrapDependencies(shrinkwrapLockfile)
                else ->
                    resolveNpmDependencies(parent)
            }
        }

        return result
    }

    fun resolveNpmDependencies(parent: File): Dependency {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    /**
     * Resolve dependencies using the npm-shrinkwrap.json file. Does not support detection of scope, all dependencies
     * are marked as production dependencies. Because the shrinkwrap file does not contain information about the
     * dependency tree all dependencies are added as top-level dependencies.
     */
    fun resolveShrinkwrapDependencies(lockfile: File): Dependency {
        val jsonObject = Parser().parse(lockfile.inputStream()) as JsonObject
        val name = jsonObject.string("name")!!
        val version = jsonObject.string("version")!!
        val jsonDependencies = jsonObject.obj("dependencies")
        val dependencies = mutableListOf<Dependency>()
        jsonDependencies!!.map.forEach { name, versionObject ->
            val version = (versionObject as JsonObject).string("version")!!
            val dependency = Dependency(artifact = name, version = version, dependencies = listOf(),
                    scope = "production")
            dependencies.add(dependency)
        }
        return Dependency(artifact = name, version = version, dependencies = dependencies, scope = "production")
    }

    /**
     * Resolve dependencies using yarn. Does not support detection of scope, all dependencies are marked as production
     * dependencies.
     */
    fun resolveYarnDependencies(parent: File): Dependency {
        val command = if (OS.isWindows) "yarn.cmd" else "yarn"
        val process = ProcessBuilder(command, "list", "--json", "--no-progress").directory(parent).start()
        if (process.waitFor() != 0) {
            throw Exception("Yarn failed with exit code ${process.exitValue()}.")
        }
        val jsonObject = Parser().parse(process.inputStream) as JsonObject
        val data = jsonObject.obj("data")!!
        val jsonDependencies = data.array<JsonObject>("trees")!!
        val dependencies = parseYarnDependencies(jsonDependencies)
        // The "todo"s below will be replaced once parsing of package.json is implemented.
        return Dependency(artifact = "todo", version = "todo", dependencies = dependencies,
                scope = "production")
    }

    private fun parseYarnDependencies(jsonDependencies: JsonArray<JsonObject>): List<Dependency> {
        val result = mutableListOf<Dependency>()
        jsonDependencies.forEach { jsonDependency ->
            val data = jsonDependency.string("name")!!.split("@")
            val children = jsonDependency.array<JsonObject>("children")
            val dependencies = if (children != null) parseYarnDependencies(children) else listOf()
            val dependency = Dependency(artifact = data[0], version = data[1], dependencies = dependencies,
                    scope = "production")
            result.add(dependency)
        }
        return result
    }
}

object PIP : PackageManager(
        "https://pip.pypa.io/",
        "Python",
        // See https://caremad.io/posts/2013/07/setup-vs-requirement/.
        listOf("setup.py", "requirements*.txt")
) {
    override fun resolveDependencies(definitionFiles: List<Path>): Map<Path, Dependency> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

object SBT : PackageManager(
        "http://www.scala-sbt.org/",
        "Scala",
        listOf("build.sbt", "build.scala")
) {
    override fun resolveDependencies(definitionFiles: List<Path>): Map<Path, Dependency> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

object ProvenanceAnalyzer {
    private val logger = KotlinLogging.logger {}

    // Prioritized list of package managers.
    val PACKAGE_MANAGERS = listOf(
            Gradle,
            Maven,
            SBT,
            NPM,
            CocoaPods,
            Godep,
            Bower,
            PIP,
            Bundler
    )

    class PackageManagerListConverter : IStringConverter<List<PackageManager>> {
        override fun convert(managers: String): List<PackageManager> {
            // Map lower-cased package manager class names to their instances.
            val packageManagerNames = packageManagers.associateBy { it.javaClass.name.toLowerCase() }

            // Determine active package managers.
            val names = managers.toLowerCase().split(",")
            return names.mapNotNull { packageManagerNames[it] }
        }
    }

    @Parameter(names = arrayOf("--package-managers", "-m"), description = "A list of package managers to activate.", listConverter = PackageManagerListConverter::class, order = 0)
    var packageManagers: List<PackageManager> = PACKAGE_MANAGERS

    @Parameter(names = arrayOf("--help", "-h"), description = "Display the command line help.", help = true, order = 100)
    var help = false

    @Parameter(description = "project path(s)")
    var projectPaths: List<String>? = null

    @JvmStatic
    fun main(args: Array<String>) {
        val jc = JCommander(this)
        jc.parse(*args)
        jc.programName = "pran"

        if (help) {
            jc.usage()
            exitProcess(1)
        }

        if (projectPaths == null) {
            logger.error("Please specify at least one project path.")
            exitProcess(2)
        }

        println("The following package managers are activated:")
        println("\t" + packageManagers.map { it.javaClass.name }.joinToString(", "))

        // Map of paths managed by the respective package manager.
        val managedProjectPaths = HashMap<PackageManager, MutableList<Path>>()

        projectPaths!!.forEach { projectPath ->
            val absolutePath = Paths.get(projectPath).toAbsolutePath()
            println("Scanning project path '$absolutePath'.")

            if (packageManagers.size == 1 && Files.isRegularFile(absolutePath)) {
                // If only one package manager is activated, treat given paths to files as definition files for that
                // package manager despite their name.
                managedProjectPaths.getOrPut(packageManagers.first()) { mutableListOf() }.add(absolutePath)
            } else {
                Files.walkFileTree(absolutePath, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        packageManagers.forEach { manager ->
                            if (manager.globForDefinitionFiles.matches(file)) {
                                managedProjectPaths.getOrPut(manager) { mutableListOf() }.add(file)
                            }
                        }

                        return FileVisitResult.CONTINUE
                    }
                })
            }
        }

        managedProjectPaths.forEach { manager, paths ->
            println("$manager projects found in:")
            println(paths.map { "\t$it" }.joinToString("\n"))

            // Print a sorted, de-duplicated list of dependencies.
            val dependencies = manager.resolveDependencies(paths)
            dependencies.forEach(::println)
        }
    }
}
