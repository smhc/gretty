package gretty

import org.gradle.api.*
import org.gradle.api.plugins.*
import org.gradle.api.tasks.*
import org.gradle.api.tasks.bundling.*

final class GrettyRunner {

  public static void run(Map params = [:]) {

    Project project = params.project

    def urls = []

    def addProjectClassPath = { Project proj ->
      urls.add new File(proj.buildDir, 'classes/main').toURI().toURL()
      urls.add new File(proj.buildDir, 'resources/main').toURI().toURL()
      urls.addAll proj.configurations.runtime.collect { dep -> dep.toURI().toURL() }
    }

    urls.addAll project.configurations.grettyConfig.collect { dep -> dep.toURI().toURL() }
    if(params.inplace) {
      addProjectClassPath project
      for(Project overlay in project.gretty.overlays.reverse())
        addProjectClassPath overlay
    }
    ClassLoader classLoader = new URLClassLoader(urls as URL[])

    def helper = classLoader.findClass('gretty.GrettyHelper').newInstance()

    def server = helper.createServer()
    helper.createConnectors server, project.gretty.port

    def context = helper.createWebAppContext()
    helper.setClassLoader context, classLoader

    String realm = project.gretty.realm
    String realmConfigFile = project.gretty.realmConfigFile
    if(realmConfigFile && !new File(realmConfigFile).isAbsolute())
      realmConfigFile = "${project.webAppDir.absolutePath}/${realmConfigFile}"
    if(!realm || !realmConfigFile)
      for(Project overlay in project.gretty.overlays.reverse())
        if(overlay.gretty.realm && overlay.gretty.realmConfigFile) {
          realm = overlay.gretty.realm
          realmConfigFile = overlay.gretty.realmConfigFile
          if(realmConfigFile && !new File(realmConfigFile).isAbsolute())
            realmConfigFile = "${overlay.webAppDir.absolutePath}/${realmConfigFile}"
          break
        }
    if(realm && realmConfigFile)
      helper.setRealm context, realm, realmConfigFile

    String contextPath = project.gretty.contextPath
    if(!contextPath)
      for(Project overlay in project.gretty.overlays.reverse())
        if(overlay.gretty.contextPath) {
          contextPath = overlay.gretty.contextPath
          break
        }
    helper.setContextPath context, contextPath ?: '/'

    for(Project overlay in project.gretty.overlays)
      for(def e in overlay.gretty.initParameters) {
        def paramValue = e.value
        if(paramValue instanceof Closure)
          paramValue = paramValue()
        helper.setInitParameter context, e.key, paramValue
      }
    for(def e in project.gretty.initParameters) {
      def paramValue = e.value
      if(paramValue instanceof Closure)
        paramValue = paramValue()
      helper.setInitParameter context, e.key, paramValue
    }

    if(params.inplace)
      helper.setResourceBase context, "${project.buildDir}/webapp"
    else
      helper.setWar context, project.tasks.war.archivePath.toString()

    helper.setHandler server, context

    helper.startServer server

    boolean interactive = params.interactive

    System.out.println 'Jetty server started.'
    System.out.println 'You can see web-application in browser under the address:'
    System.out.println "http://localhost:${project.gretty.port}${project.gretty.contextPath}"
    for(Project overlay in project.gretty.overlays)
      overlay.gretty.onStart.each { onStart ->
        if(onStart instanceof Closure)
          onStart()
      }
    project.gretty.onStart.each { onStart ->
      if(onStart instanceof Closure)
        onStart()
    }
    if(interactive)
      System.out.println 'Press any key to stop the jetty server.'
    else
      System.out.println 'Enter \'gradle jettyStop\' to stop the jetty server.'
    System.out.println()

    if(interactive) {
      System.in.read()
      helper.stopServer server
    } else {
      Thread monitor = new JettyMonitorThread(project.gretty.servicePort, helper, server)
      monitor.start()
    }

    server.join()

    System.out.println 'Jetty server stopped.'
    project.gretty.onStop.each { onStop ->
      if(onStop instanceof Closure)
        onStop()
    }
    for(Project overlay in project.gretty.overlays.reverse())
      overlay.gretty.onStop.each { onStop ->
        if(onStop instanceof Closure)
          onStop()
      }
  }

  public static void sendServiceCommand(int servicePort, String command) {
    Socket s = new Socket(InetAddress.getByName('127.0.0.1'), servicePort)
    try {
      OutputStream out = s.getOutputStream()
      System.out.println "Sending command: ${command}"
      out.write(("${command}\n").getBytes())
      out.flush()
    } finally {
      s.close()
    }
  }
}
