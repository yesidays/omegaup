package omegaup.runner

import java.io._
import java.util.zip._
import java.util.logging._
import javax.servlet._
import javax.servlet.http._
import org.mortbay.jetty._
import org.mortbay.jetty.handler._
import net.liftweb.json._
import scala.collection.{mutable,immutable}
import omegaup._
import omegaup.data._

object Runner extends RunnerService with Log {
	def compile(message: CompileInputMessage): CompileOutputMessage = {
		// lang: String, code: List[String], master_lang: Option[String], master_code: Option[List[String]]
		info("compile {}", message.lang)
		
		val compileDirectory = new File(Config.get("compile.root", "."))
		compileDirectory.mkdirs
		
		var runDirectory = File.createTempFile(System.nanoTime.toString, null, compileDirectory)
		runDirectory.delete
		
		runDirectory = new File(runDirectory.getCanonicalPath.substring(0, runDirectory.getCanonicalPath.length - 4) + "." + message.lang + "/bin")
		runDirectory.mkdirs
		
		var fileWriter = new FileWriter(runDirectory.getCanonicalPath + "/Main." + message.lang)
		fileWriter.write(message.code(0), 0, message.code(0).length)
		fileWriter.close
		var inputFiles = mutable.ListBuffer(runDirectory.getCanonicalPath + "/Main." + message.lang)
		
		for (i <- 1 until message.code.length) {
			fileWriter = new FileWriter(runDirectory.getCanonicalPath + "/f" + i + "." + message.lang)
			inputFiles += runDirectory.getCanonicalPath + "/f" + i + "." + message.lang
			fileWriter.write(message.code(i), 0, message.code(i).length)
			fileWriter.close
		}
		
		val sandbox = Config.get("runner.sandbox.path", ".") + "/box"
		val profile = Config.get("runner.sandbox.path", ".") + "/profiles"
		val runtime = Runtime.getRuntime
		
		val process = message.lang match {
			case "java" =>
				runtime.exec((List(sandbox, "-S", profile + "/javac", "-c", runDirectory.getCanonicalPath, "-q", "-M", runDirectory.getCanonicalPath + "/compile.meta", "-o", "compile.out", "-r", "compile.err", "-t", Config.get("java.compile.time_limit", "30"), "--", Config.get("java.compiler.path", "/usr/bin/javac")) ++ inputFiles).toArray)
			case "c" =>
				runtime.exec((List(sandbox, "-S", profile + "/gcc", "-c", runDirectory.getCanonicalPath, "-q", "-m", "524288", "-M", runDirectory.getCanonicalPath + "/compile.meta", "-o", "compile.out", "-r", "compile.err", "--", Config.get("c.compiler.path", "/usr/bin/gcc"), "-ansi", "-O2", "-lm") ++ inputFiles).toArray)
			case "cpp" =>
				runtime.exec((List(sandbox, "-S", profile + "/gcc", "-c", runDirectory.getCanonicalPath, "-q", "-m", "524288", "-M", runDirectory.getCanonicalPath + "/compile.meta", "-o", "compile.out", "-r", "compile.err", "--", Config.get("cpp.compiler.path", "/usr/bin/g++"), "-O2", "-lm") ++ inputFiles).toArray)
			case _ => null
		}
		
		if(process != null) {
			val status = process.waitFor
		
			inputFiles.foreach { new File(_).delete }
			
			if (status == 0) {
				new File(runDirectory.getCanonicalPath + "/compile.meta").delete
				new File(runDirectory.getCanonicalPath + "/compile.out").delete
				new File(runDirectory.getCanonicalPath + "/compile.err").delete
			
				info("compile finished successfully")
				new CompileOutputMessage(token = Some(runDirectory.getParentFile.getName))
			} else {
				val meta = MetaFile.load(runDirectory.getCanonicalPath + "/compile.meta")
			
				val compileError =
					if (meta("status") == "TO")
						"Compilation time exceeded"
					else if (meta.contains("message") && meta("status") != "RE")
						meta("message")
					else
						FileUtil.read(runDirectory.getCanonicalPath + "/compile.err").replace(runDirectory.getCanonicalPath + "/", "")
				
				FileUtil.deleteDirectory(runDirectory.getParentFile.getCanonicalPath)
				
				info("compile finished with errors: {}", compileError)
				new CompileOutputMessage("compile error", error=Some(compileError))
			}
		} else {
			info("compile finished successfully")
			new CompileOutputMessage(token = Some(runDirectory.getParentFile.getName))
		}
	}
	
	def run(message: RunInputMessage, zipFile: File) : Option[RunOutputMessage] = {
		info("run {}", message)
		val casesDirectory:File = message.input match {
			case Some(in) => {
				if (in.contains(".") || in.contains("/")) throw new IllegalArgumentException("Invalid input")
				new File (Config.get("input.root", ".") + "/" + in)
			}
			case None => null
		}
		
		if(message.token.contains("..") || message.token.contains("/")) throw new IllegalArgumentException("Invalid token")
		
		if(casesDirectory != null && !casesDirectory.exists) {
			Some(new RunOutputMessage(error=Some("missing input")))
		} else {
			val runDirectory = new File(Config.get("compile.root", ".") + "/" + message.token)
		
			if(!runDirectory.exists) throw new IllegalArgumentException("Invalid token")
		
			val binDirectory = new File(runDirectory.getCanonicalPath + "/bin")
		
			val lang = message.token.substring(message.token.indexOf(".")+1)
		
			val sandbox = Config.get("runner.sandbox.path", ".") + "/box"
			val profile = Config.get("runner.sandbox.path", ".") + "/profiles"
			val runtime = Runtime.getRuntime
		
			if(casesDirectory != null) {
				casesDirectory.listFiles.filter {_.getName.endsWith(".in")} .foreach { (x) => {
					val caseName = runDirectory.getCanonicalPath + "/" + x.getName.substring(0, x.getName.lastIndexOf('.'))
				
					val process = lang match {
						case "java" =>
							runtime.exec((List(sandbox, "-S", profile + "/java", "-c", binDirectory.getCanonicalPath, "-q", "-M", caseName + ".meta", "-i", x.getCanonicalPath, "-o", caseName + ".out", "-r", caseName + ".err", "-t", message.timeLimit.toString, "-O", message.outputLimit.toString, "--", Config.get("java.runtime.path", "/usr/bin/java"), "-Xmx" + message.memoryLimit + "k", "Main")).toArray)
						case "c" =>
							runtime.exec((List(sandbox, "-S", profile + "/c", "-c", binDirectory.getCanonicalPath, "-q", "-M", caseName + ".meta", "-i", x.getCanonicalPath, "-o", caseName + ".out", "-r", caseName + ".err", "-t", message.timeLimit.toString, "-O", message.outputLimit.toString, "-m", message.memoryLimit.toString, "--", "./a.out")).toArray)
						case "cpp" =>
							runtime.exec((List(sandbox, "-S", profile + "/c", "-c", binDirectory.getCanonicalPath, "-q", "-M", caseName + ".meta", "-i", x.getCanonicalPath, "-o", caseName + ".out", "-r", caseName + ".err", "-t", message.timeLimit.toString, "-O", message.outputLimit.toString, "-m", message.memoryLimit.toString, "--", "./a.out")).toArray)
					}
				
					process.waitFor
				}}
			}
		
			message.cases match {
				case None => {}
				case Some(extra) => {
					extra.foreach { (x: CaseData) => {
						val caseName = x.name
						val casePath = runDirectory.getCanonicalPath + "/" + caseName
					
						FileUtil.write(casePath + ".in", x.data)
				
						val process = lang match {
							case "java" =>
								runtime.exec((List(sandbox, "-S", profile + "/java", "-c", binDirectory.getCanonicalPath, "-q", "-M", casePath + ".meta", "-i", casePath + ".in", "-o", casePath + ".out", "-r", casePath + ".err", "-t", message.timeLimit.toString, "-O", message.outputLimit.toString, "--", "/usr/bin/java", "-Xmx" + message.memoryLimit + "k", "Main")).toArray)
							case "c" =>
								runtime.exec((List(sandbox, "-S", profile + "/c", "-c", binDirectory.getCanonicalPath, "-q", "-M", casePath + ".meta", "-i", casePath + ".in", "-o", casePath + ".out", "-r", casePath + ".err", "-t", message.timeLimit.toString, "-O", message.outputLimit.toString, "-m", message.memoryLimit.toString, "--", "./a.out")).toArray)
							case "cpp" =>
								runtime.exec((List(sandbox, "-S", profile + "/c", "-c", binDirectory.getCanonicalPath, "-q", "-M", casePath + ".meta", "-i", casePath + ".in", "-o", casePath + ".out", "-r", casePath + ".err", "-t", message.timeLimit.toString, "-O", message.outputLimit.toString, "-m", message.memoryLimit.toString, "--", "./a.out")).toArray)
						}
				
						process.waitFor
					
						new File(casePath + ".in").delete
					}}
				}
			}
		
			val zipOutput = new ZipOutputStream(new FileOutputStream(zipFile.getCanonicalPath))
		
			runDirectory.listFiles.filter { _.getName.endsWith(".meta") } .foreach { (x) => {
				zipOutput.putNextEntry(new ZipEntry(x.getName))
			
				var inputStream = new FileInputStream(x.getCanonicalPath)
				val buffer = Array.ofDim[Byte](1024)
				var read: Int = 0
	
				while( { read = inputStream.read(buffer); read > 0 } ) {
					zipOutput.write(buffer, 0, read)
				}
			
				inputStream.close
				zipOutput.closeEntry
			
				val meta = MetaFile.load(x.getCanonicalPath)
			
				if(meta("status") == "OK") {
					inputStream = new FileInputStream(x.getCanonicalPath.replace(".meta", ".out"))
					zipOutput.putNextEntry(new ZipEntry(x.getName.replace(".meta", ".out")))
				
					while( { read = inputStream.read(buffer); read > 0 } ) {
						zipOutput.write(buffer, 0, read)
					}
		
					inputStream.close
					zipOutput.closeEntry
				
				} else if(meta("status") == "RE" && lang == "java") {
					inputStream = new FileInputStream(x.getCanonicalPath.replace(".meta", ".err"))
					zipOutput.putNextEntry(new ZipEntry(x.getName.replace(".meta", ".err")))
				
					while( { read = inputStream.read(buffer); read > 0 } ) {
						zipOutput.write(buffer, 0, read)
					}
		
					inputStream.close
					zipOutput.closeEntry
				}
			
				x.delete
				new File(x.getCanonicalPath.replace(".meta", ".err")).delete
				new File(x.getCanonicalPath.replace(".meta", ".out")).delete
			}}
		
			zipOutput.close
		
			info("run finished token={}", message.token)
			
			None
		}
	}
	
	def removeCompileDir(token: String): Unit = {
		val runDirectory = new File(Config.get("compile.root", ".") + "/" + token)
		
		if(!runDirectory.exists) throw new IllegalArgumentException("Invalid token")
		
		FileUtil.deleteDirectory(runDirectory)
	}
	
	def input(inputName: String, inputStream: InputStream, size: Int = -1): InputOutputMessage = {
		val inputDirectory = new File(Config.get("input.root", ".") + "/" + inputName)
		inputDirectory.mkdirs()
		
		val input = new ZipInputStream(inputStream)
		var entry: ZipEntry = input.getNextEntry
		val buffer = Array.ofDim[Byte](1024)
		var read: Int = 0
		
		while(entry != null) {
			val outFile = new File(entry.getName())
			val output = new FileOutputStream(inputDirectory.getCanonicalPath + "/" + outFile.getName)

			while( { read = input.read(buffer); read > 0 } ) {
				output.write(buffer, 0, read)
			}

			output.close
			input.closeEntry
			entry = input.getNextEntry
		}
		
		input.close
		
		new InputOutputMessage()
	}
	
	def main(args: Array[String]) = {
		// Setting keystore properties
		System.setProperty("javax.net.ssl.keyStore", Config.get("runner.keystore", "omegaup.jks"))
		System.setProperty("javax.net.ssl.trustStore", Config.get("runner.truststore", "omegaup.jks"))
		System.setProperty("javax.net.ssl.keyStorePassword", Config.get("runner.keystore.password", "omegaup"))
		System.setProperty("javax.net.ssl.trustStorePassword", Config.get("runner.truststore.password", "omegaup"))
		
		// logger
		System.setProperty("org.mortbay.log.class", "org.mortbay.log.Slf4jLog")
		if(Config.get("grader.logging.file", "") != "") {
			Logger.getLogger("").addHandler(new FileHandler(Config.get("grader.logfile", "")))
		}
		Logger.getLogger("").setLevel(
			Config.get("grader.logging.level", "info") match {
				case "all" => Level.ALL
				case "finest" => Level.FINEST
				case "finer" => Level.FINER
				case "fine" => Level.FINE
				case "config" => Level.CONFIG
				case "info" => Level.INFO
				case "warning" => Level.WARNING
				case "severe" => Level.SEVERE
				case "off" => Level.OFF
			}
		)
		Logger.getLogger("").getHandlers.foreach { _.setFormatter(LogFormatter) }

		// the handler
		val handler = new AbstractHandler() {
			@throws(classOf[IOException])
			@throws(classOf[ServletException])
			def handle(target: String, request: HttpServletRequest, response: HttpServletResponse, dispatch: Int) = {
				implicit val formats = Serialization.formats(NoTypeHints)
				
				request.getPathInfo() match {
					case "/run/" => {
						try {
							val req = Serialization.read[RunInputMessage](request.getReader)
							
							val zipFile = new File(Config.get("compile.root", ".") + "/" + req.token + "/output.zip")
							Runner.run(req, zipFile) match {
								case Some(msg: RunOutputMessage) => {
									response.setContentType("text/json")
									response.setStatus(HttpServletResponse.SC_OK)
									
									Serialization.write(msg, response.getWriter())
								}
								case _ => {
									response.setContentType("application/zip")
									response.setStatus(HttpServletResponse.SC_OK)
									response.setContentLength(zipFile.length.asInstanceOf[Int])
							
									val input = new FileInputStream(zipFile)
									val output = response.getOutputStream
									val buffer = Array.ofDim[Byte](1024)
									var read: Int = 0
		
									while( { read = input.read(buffer); read > 0 } ) {
										output.write(buffer, 0, read)
									}

									input.close
									output.close
							
									Runner.removeCompileDir(req.token)
								}
							}
						} catch {
							case e: Exception => {
								error("/run/", e)
								response.setContentType("text/json")
								response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
								Serialization.write(new RunOutputMessage(status = "error", error = Some(e.getMessage)), response.getWriter())
							}
						}
					}
					case _ => {
						response.setContentType("text/json")
						Serialization.write(request.getPathInfo() match {
							case "/compile/" => {
								try {
									val req = Serialization.read[CompileInputMessage](request.getReader())
									response.setStatus(HttpServletResponse.SC_OK)
									Runner.compile(req)
								} catch {
									case e: Exception => {
										error("/compile/", e)
										response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
										new CompileOutputMessage(status = "error", error = Some(e.getMessage))
									}
								}
							}
							case "/input/" => {
								try {
									info("/input/")
									
									response.setStatus(HttpServletResponse.SC_OK)
									if(request.getContentType() != "application/zip" || request.getHeader("Content-Disposition") == null) {
										new InputOutputMessage(
											status = "error",
											error = Some("Content-Type must be \"application/zip\", Content-Disposition must be \"attachment\" and a filename must be specified")
										)
									} else {
										val ContentDispositionRegex = "attachment; filename=([a-zA-Z0-9_-][a-zA-Z0-9_.-]*);.*".r
			
										val ContentDispositionRegex(inputName) = request.getHeader("Content-Disposition")
										Runner.input(inputName, request.getInputStream)
									}
								} catch {
									case e: Exception => {
										error("/inpue/", e)
										response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
										new InputOutputMessage(status = "error", error = Some(e.getMessage))
									}
								}
							}
							case _ => {
								response.setStatus(HttpServletResponse.SC_NOT_FOUND)
								new NullMessage()
							}
						}, response.getWriter())
					}
				}
				
				request.asInstanceOf[Request].setHandled(true)
			}
		};

		// boilerplate code for jetty with https support	
		val server = new Server()
		
		val runnerConnector = new org.mortbay.jetty.security.SslSelectChannelConnector
		runnerConnector.setPort(Config.get("runner.port", 0))
		runnerConnector.setKeystore(Config.get("runner.keystore", "omegaup.jks"))
		runnerConnector.setPassword(Config.get("runner.password", "omegaup"))
		runnerConnector.setKeyPassword(Config.get("runner.keystore.password", "omegaup"))
		runnerConnector.setTruststore(Config.get("runner.truststore", "omegaup.jks"))
		runnerConnector.setTrustPassword(Config.get("runner.truststore.password", "omegaup"))
		runnerConnector.setNeedClientAuth(true)
		
		server.setConnectors(List(runnerConnector).toArray)
		
		server.setHandler(handler)
		server.start()
		
		info("Registering port {}", runnerConnector.getLocalPort())
		
		Https.send[RegisterOutputMessage, RegisterInputMessage](
			Config.get("grader.register.url", "https://localhost:21680/register/"),
			new RegisterInputMessage(runnerConnector.getLocalPort())
		)
		
		java.lang.System.in.read()
		
		try {
			// well, at least try to de-register
			Https.send[RegisterOutputMessage, RegisterInputMessage](
				Config.get("grader.deregister.url", "https://localhost:21680/deregister/"),
				new RegisterInputMessage(runnerConnector.getLocalPort())
			)
		} catch {
			case _ => {}
		}
	
		server.stop()
		server.join()
	}
}

