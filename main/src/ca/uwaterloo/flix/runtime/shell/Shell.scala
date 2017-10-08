/*
 * Copyright 2017 Magnus Madsen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.uwaterloo.flix.runtime.shell

import java.nio.file._
import java.util.concurrent.Executors

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.Symbol
import ca.uwaterloo.flix.language.ast.TypedAst._
import ca.uwaterloo.flix.runtime.Model
import ca.uwaterloo.flix.util._
import ca.uwaterloo.flix.util.vt.VirtualString._
import ca.uwaterloo.flix.util.vt.{TerminalContext, VirtualTerminal}

import scala.collection.JavaConverters._
import scala.collection.mutable

class Shell(initialPaths: List[Path], main: Option[String], options: Options) {

  /**
    * The minimum amount of time between runs of the compiler.
    */
  private val Delay: Long = 1000 * 1000 * 1000

  /**
    * The default color context.
    */
  private implicit val _ = TerminalContext.AnsiTerminal

  /**
    * The executor service.
    */
  private val executorService = Executors.newSingleThreadExecutor()

  /**
    * The mutable set of paths to load.
    */
  private val sourcePaths = mutable.Set.empty[Path] ++ initialPaths

  /**
    * The current flix instance (initialized on startup).
    */
  private var flix: Flix = _

  /**
    * The current typed ast root (initialized on startup).
    */
  private var root: Root = _

  /**
    * The current model (if any).
    */
  private var model: Model = _

  /**
    * The current watcher (if any).
    */
  private var watcher: WatcherThread = _

  /**
    * The current expression (if any).
    */
  private var expression: String = _

  /**
    * Continuously reads a line of input from the input stream, parses and executes it.
    */
  def loop(): Unit = {
    // Print welcome banner.
    printWelcomeBanner()

    // Initialize flix.
    execReload()

    // Loop forever.
    while (!Thread.currentThread().isInterrupted) {
      Console.print(prompt)
      Console.flush()
      val line = scala.io.StdIn.readLine()
      val cmd = Command.parse(line)
      try {
        execute(cmd)
      } catch {
        case e: Exception =>
          Console.println(e.getMessage)
          e.printStackTrace()
      }
    }
  }


  /**
    * Executes the given command `cmd`
    */
  private def execute(cmd: Command): Unit = cmd match {
    case Command.Nop => // nop

    case Command.Eof | Command.Quit =>
      Console.println("Thanks, and goodbye.")
      Thread.currentThread().interrupt()

    case Command.Eval(s) =>
      expression = s.trim
      execReload()
      execSolve()
      Console.println(model.evalToString("$1"))

    case Command.ListRel => execListRel()

    case Command.ListLat => execListLat()

    case Command.Load(s) => execLoad(s)

    case Command.Unload(s) => execUnload(s)

    case Command.Reload => execReload()

    case Command.Browse(nsOpt) => execBrowse(nsOpt)

    case Command.Help => execHelp()

    case Command.Search(s) => execSearch(s)

    case Command.Solve => execSolve()

    case Command.ShowRel(fqn, needle) => execShowRel(fqn, needle)

    case Command.ShowLat(fqn, needle) => execShowLat(fqn, needle)

    case Command.Watch =>
      // Check if the watcher is already initialized.
      if (watcher != null)
        return

      // Compute the set of directories to watch.
      val directories = sourcePaths.map(_.toAbsolutePath.getParent).toList

      // Print debugging information.
      Console.println("Watching Directories:")
      for (directory <- directories) {
        Console.println(s"  $directory")
      }

      watcher = new WatcherThread(directories)
      watcher.start()

    case Command.Unwatch =>
      watcher.interrupt()
      Console.println("Unwatched loaded paths.")

    case Command.Unknown(s) => Console.println(s"Unknown command '$s'. Try `help'.")
  }

  /**
    * Prints the welcome banner to the console.
    */
  private def printWelcomeBanner(): Unit = {
    val banner =
      """     __  _  _
        |    / _|| |(_)            Welcome to Flix __VERSION__
        |   | |_ | | _ __  __
        |   |  _|| || |\ \/ /      Enter a command and hit return.
        |   | |  | || | >  <       Type ':help' for more information.
        |   |_|  |_||_|/_/\_\      Type ':quit' or press ctrl+d to exit.
      """.stripMargin

    Console.println(banner.replaceAll("__VERSION__", Version.CurrentVersion.toString))
  }

  /**
    * Prints the prompt.
    */
  private def prompt: String = "flix> "

  /**
    * Executes the browse command.
    */
  private def execBrowse(nsOpt: Option[String]): Unit = nsOpt match {
    case None =>
      // Case 1: Browse available namespaces.

      // Construct a new virtual terminal.
      val vt = new VirtualTerminal

      // Find the available namespaces.
      val namespaces = namespacesOf(root)

      vt << Bold("Namespaces:") << Indent << NewLine << NewLine
      for (namespace <- namespaces.toList.sorted) {
        vt << namespace << NewLine
      }
      vt << Dedent << NewLine

      // Print the virtual terminal to the console.
      Console.print(vt.fmt)

    case Some(ns) =>
      // Case 2: Browse a specific namespace.

      // Construct a new virtual terminal.
      val vt = new VirtualTerminal

      // Print the matched definitions.
      val matchedDefs = getDefinitionsByNamespace(ns, root)
      if (matchedDefs.nonEmpty) {
        vt << Bold("Definitions:") << Indent << NewLine << NewLine
        for (defn <- matchedDefs.sortBy(_.sym.name)) {
          vt << Bold("def ") << Blue(defn.sym.name) << "("
          if (defn.fparams.nonEmpty) {
            vt << defn.fparams.head.sym.text << ": " << Cyan(defn.fparams.head.tpe.toString)
            for (fparam <- defn.fparams.tail) {
              vt << ", " << fparam.sym.text << ": " << Cyan(fparam.tpe.toString)
            }
          }
          vt << "): " << Cyan(defn.tpe.typeArguments.last.toString) << NewLine
        }
        vt << Dedent << NewLine
      }

      // Print the matched relations.
      val matchedRels = getRelationsByNamespace(ns, root)
      if (matchedRels.nonEmpty) {
        vt << Bold("Relations:") << Indent << NewLine << NewLine
        for (rel <- matchedRels.sortBy(_.sym.name)) {
          vt << Bold("rel ") << Blue(rel.sym.toString) << "("
          vt << rel.attributes.head.name << ": " << Cyan(rel.attributes.head.tpe.toString)
          for (attr <- rel.attributes.tail) {
            vt << ", " << attr.name << ": " << Cyan(attr.tpe.toString)
          }
          vt << ")" << NewLine
        }
        vt << Dedent << NewLine
      }

      // Print the matched lattices.
      val matchedLats = getLatticesByNamespace(ns, root)
      if (matchedLats.nonEmpty) {
        vt << Bold("Lattices:") << Indent << NewLine << NewLine
        for (lat <- matchedLats.sortBy(_.sym.name)) {
          vt << Bold("lat ") << Blue(lat.sym.toString) << "("
          vt << lat.attributes.head.name << ": " << Cyan(lat.attributes.head.tpe.toString)
          for (attr <- lat.attributes.tail) {
            vt << ", " << attr.name << ": " << Cyan(attr.tpe.toString)
          }
          vt << ")" << NewLine
        }
        vt << Dedent << NewLine
      }

      // Print the virtual terminal to the console.
      Console.print(vt.fmt)
  }

  /**
    * Executes the help command.
    */
  private def execHelp(): Unit = {
    // TODO: Updte
    Console.println("  Command    Alias    Arguments        Description")
    Console.println()
    Console.println("  :reload    :r                        reload and compile the loaded paths.")
    Console.println("  :browse             <ns>             shows the definitions in the given namespace.")
    Console.println("  :load               <path>           loads the given path.")
    Console.println("  :unload             <path>           unloads the given path.")
    Console.println("  :rel                [fqn]            shows all relations or the content of one relation.")
    Console.println("  :lat                [fqn]            shows all lattices or the content of one lattice.")
    Console.println("  :search             name             search for a symbol with the given name.")
    Console.println("  :solve                               computes the least fixed point.")
    Console.println("  :quit      :q                        shutdown.")
    Console.println("  :watch     :w                        watch loaded paths for changes.")
    Console.println("  :unwatch   :w                        unwatch loaded paths for changes.")
  }

  /**
    * Lists all relations in the program.
    */
  private def execListRel(): Unit = {
    // Construct a new virtual terminal.
    val vt = new VirtualTerminal
    vt << Bold("Relations:") << Indent << NewLine << NewLine
    // Iterate through all tables in the program.
    for ((_, table) <- root.tables) {
      table match {
        case Table.Relation(doc, sym, attributes, loc) =>
          vt << Blue(sym.name) << "("
          vt << attributes.head.name << ": " << Cyan(attributes.head.tpe.toString)
          for (attribute <- attributes.tail) {
            vt << ", "
            vt << attribute.name << ": " << Cyan(attribute.tpe.toString)
          }
          vt << ")" << NewLine
        case Table.Lattice(doc, sym, keys, value, loc) => // nop
      }
    }
    vt << Dedent << NewLine
    // Print the virtual terminal to the console.
    Console.print(vt.fmt)
  }


  /**
    * Lists all lattices in the program.
    */
  private def execListLat(): Unit = {
    // Construct a new virtual terminal.
    val vt = new VirtualTerminal

    vt << Bold("Lattices:") << Indent << NewLine << NewLine
    // Iterate through all tables in the program.
    for ((_, table) <- root.tables) {
      table match {
        case Table.Relation(doc, sym, attributes, loc) => // nop
        case Table.Lattice(doc, sym, keys, value, loc) =>
          val attributes = keys ::: value :: Nil
          vt << Blue(sym.name) << "("
          vt << attributes.head.name << ": " << Cyan(attributes.head.tpe.toString)
          for (attribute <- attributes.tail) {
            vt << ", "
            vt << attribute.name << ": " << Cyan(attribute.tpe.toString)
          }
          vt << ")" << NewLine
      }
    }
    vt << Dedent << NewLine

    // Print the virtual terminal to the console.
    Console.print(vt.fmt)
  }


  /**
    * Executes the load command.
    */
  private def execLoad(s: String): Unit = {
    val path = Paths.get(s)
    if (!Files.exists(path) || !Files.isRegularFile(path)) {
      Console.println(s"Path '$path' does not exist or is not a regular file.")
      return
    }
    sourcePaths += path
    Console.println(s"Path '$path' was loaded.")
  }

  /**
    * Executes the unload command.
    */
  private def execUnload(s: String): Unit = {
    val path = Paths.get(s)
    if (!(sourcePaths contains path)) {
      Console.println(s"Path '$path' was not loaded.")
      return
    }
    sourcePaths -= path
    Console.println(s"Path '$path' was unloaded.")
  }

  /**
    * Reloads every loaded path.
    */
  private def execReload(): Unit = {
    // Instantiate a fresh flix instance.
    flix = new Flix()
    flix.setOptions(options)
    for (path <- sourcePaths) {
      flix.addPath(path)
    }
    if (expression != null) {
      flix.addNamedExp(Symbol.mkDefnSym("$1"), expression)
    }

    // Check if a main function was given.
    // TODO: Deprecated.
    if (main.nonEmpty) {
      val name = main.get
      flix.addReachableRoot(name)
    }

    // Print the error messages (if any).
    flix.check() match {
      case Validation.Success(ast, _) =>
        this.root = ast
      case Validation.Failure(errors) =>
        errors.foreach(e => println(e.message.fmt))
    }

  }

  /**
    * Searches for the given `needle`.
    */
  private def execSearch(needle: String): Unit = {
    // Construct a new virtual terminal.
    val vt = new VirtualTerminal
    vt << Bold("Definitions:") << Indent << NewLine << NewLine
    // TODO: Group by namespace.
    for ((sym, defn) <- root.defs) {
      if (sym.name.toLowerCase().contains(needle)) {
        vt << Bold("def ") << Blue(defn.sym.name) << "("
        if (defn.fparams.nonEmpty) {
          vt << defn.fparams.head.sym.text << ": " << Cyan(defn.fparams.head.tpe.toString)
          for (fparam <- defn.fparams.tail) {
            vt << ", " << fparam.sym.text << ": " << Cyan(fparam.tpe.toString)
          }
        }
        vt << "): " << Cyan(defn.tpe.typeArguments.last.toString) << NewLine
      }
    }

    vt << Dedent << NewLine
    // Print the virtual terminal to the console.
    Console.print(vt.fmt)
  }

  /**
    * Computes the least fixed point.
    */
  private def execSolve(): Unit = {
    val future = executorService.submit(new SolverThread())
    future.get()
  }

  /**
    * Shows the rows in the given relation `fqn` that match the optional `needle`.
    */
  private def execShowRel(fqn: String, needle: Option[String]): Unit = {
    model.getRelations.get(fqn) match {
      case None =>
        Console.println(s"Undefined relation: '$fqn'.")
      case Some((attributes, rows)) =>
        val ascii = new AsciiTable().withCols(attributes: _*).withFilter(needle)
        for (row <- rows) {
          ascii.mkRow(row)
        }
        ascii.write(System.out)
    }
  }

  /**
    * Shows the rows in the given lattice `fqn` that match the optional `needle`.
    */
  private def execShowLat(fqn: String, needle: Option[String]): Unit = {
    model.getLattices.get(fqn) match {
      case None =>
        Console.println(s"Undefined lattice: '$fqn'.")
      case Some((attributes, rows)) =>
        val ascii = new AsciiTable().withCols(attributes: _*).withFilter(needle)
        for (row <- rows) {
          ascii.mkRow(row)
        }
        ascii.write(System.out)
    }
  }

  /**
    * Returns the namespaces in the given AST `root`.
    */
  private def namespacesOf(root: Root): Set[String] = {
    val ns1 = root.defs.keySet.map(_.namespace.mkString("/"))
    val ns2 = root.tables.keySet.map(_.namespace.mkString("/"))
    (ns1 ++ ns2) - ""
  }

  /**
    * Returns the definitions in the given namespace.
    */
  private def getDefinitionsByNamespace(ns: String, root: Root): List[Def] = {
    val namespace: List[String] = getNameSpace(ns)
    root.defs.foldLeft(Nil: List[Def]) {
      case (xs, (sym, defn)) if sym.namespace == namespace && !defn.mod.isSynthetic =>
        defn :: xs
      case (xs, _) => xs
    }
  }

  /**
    * Returns the relations in the given namespace.
    */
  private def getRelationsByNamespace(ns: String, root: Root): List[Table.Relation] = {
    val namespace: List[String] = getNameSpace(ns)
    root.tables.foldLeft(Nil: List[Table.Relation]) {
      case (xs, (sym, t: Table.Relation)) if sym.namespace == namespace =>
        t :: xs
      case (xs, _) => xs
    }
  }

  /**
    * Returns the lattices in the given namespace.
    */
  private def getLatticesByNamespace(ns: String, root: Root): List[Table.Lattice] = {
    val namespace: List[String] = getNameSpace(ns)
    root.tables.foldLeft(Nil: List[Table.Lattice]) {
      case (xs, (sym, t: Table.Lattice)) if sym.namespace == namespace =>
        t :: xs
      case (xs, _) => xs
    }
  }

  /**
    * Interprets the given string `ns` as a namespace.
    */
  private def getNameSpace(ns: String): List[String] = {
    if (ns == "" || ns == ".")
    // Case 1: The empty namespace.
      Nil
    else if (!ns.contains(".")) {
      // Case 2: A simple namespace.
      List(ns)
    } else {
      // Case 3: A complex namespace.
      val index = ns.indexOf('.')
      ns.substring(0, index).split('/').toList
    }
  }

  /**
    * A thread to run the fixed point solver in.
    */
  class SolverThread() extends Runnable {
    override def run(): Unit = {
      // compute the least model.
      val timer = new Timer(flix.solve())
      timer.getResult match {
        case Validation.Success(m, errors) =>
          model = m
          if (main.nonEmpty) {
            val name = main.get
            val evalTimer = new Timer(m.evalToString(name))
            Console.println(s"$name returned `${evalTimer.getResult}' (compile: ${timer.fmt}, execute: ${evalTimer.fmt})")
          }
        case Validation.Failure(errors) =>
          errors.foreach(e => println(e.message.fmt))
      }
    }
  }

  /**
    * A thread to watch over changes in a collection of directories.
    */
  class WatcherThread(paths: List[Path]) extends Thread {

    // Initialize a new watcher service.
    val watchService: WatchService = FileSystems.getDefault.newWatchService

    // Register each directory.
    for (path <- paths) {
      if (Files.isDirectory(path)) {
        path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)
      }
    }

    override def run(): Unit = try {
      // Record the last timestamp of a change.
      var lastChanged = System.nanoTime()

      // Loop until interrupted.
      while (!Thread.currentThread().isInterrupted) {
        // Wait for a set of events.
        val watchKey = watchService.take()
        // Iterate through each event.
        for (event <- watchKey.pollEvents().asScala) {
          // Check if a file with ".flix" extension changed.
          val changedPath = event.context().asInstanceOf[Path]
          if (changedPath.toString.endsWith(".flix")) {
            println(s"File: '$changedPath' changed.")
          }
        }

        // Check if sufficient time has passed since the last compilation.
        val currentTime = System.nanoTime()
        if ((currentTime - lastChanged) >= Delay) {
          lastChanged = currentTime
          // Allow a short delay before running the compiler.
          Thread.sleep(50)
          executorService.submit(new Runnable {
            def run(): Unit = execReload()
          })
        }

        // Reset the watch key.
        watchKey.reset()
      }
    } catch {
      case ex: InterruptedException => // nop, shutdown.
    }

  }

}

