/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.elodina.mesos.dse

import java.io._
import javax.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}

import org.apache.log4j.Logger
import org.eclipse.jetty.server.{Server, ServerConnector}
import org.eclipse.jetty.servlet.{ServletContextHandler, ServletHolder}
import org.eclipse.jetty.util.thread.QueuedThreadPool

import scala.util.parsing.json.{JSONArray, JSONObject}
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import net.elodina.mesos.dse.Node.State
import net.elodina.mesos.dse.Util.Period

object HttpServer {
  private val logger = Logger.getLogger(HttpServer.getClass)
  private var server: Server = null

  def start() {
    if (server != null) throw new IllegalStateException("HttpServer already started")

    val threadPool = new QueuedThreadPool(16)
    threadPool.setName("Jetty")

    server = new Server(threadPool)
    val connector = new ServerConnector(server)
    connector.setPort(Config.httpServerPort)
    connector.setIdleTimeout(60 * 1000)

    val handler = new ServletContextHandler
    handler.addServlet(new ServletHolder(Servlet), "/")

    server.setHandler(handler)
    server.addConnector(connector)
    server.start()

    logger.info("started on port " + connector.getPort)
  }

  def stop() {
    if (server == null) throw new IllegalStateException("HttpServer not started")

    server.stop()
    server.join()
    server = null

    logger.info("HttpServer stopped")
  }

  object Servlet extends HttpServlet {
    override def doPost(request: HttpServletRequest, response: HttpServletResponse) = doGet(request, response)

    override def doGet(request: HttpServletRequest, response: HttpServletResponse) {
      val url = request.getRequestURL + (if (request.getQueryString != null) "?" + request.getQueryString else "")
      logger.info("handling - " + url)

      try {
        handle(request, response)
        logger.info("finished handling")
      } catch {
        case e: HttpError =>
          response.sendError(e.getCode, e.getMessage)
        case e: Exception =>
          logger.error("error handling", e)
          response.sendError(500, "" + e)
      }
    }

    def handle(request: HttpServletRequest, response: HttpServletResponse) {
      val uri = request.getRequestURI
      if (uri.startsWith("/health")) handleHealth(response)
      else if (uri.startsWith("/jar/")) downloadFile(Config.jar, response)
      else if (uri.startsWith("/dse/")) downloadFile(Config.dse, response)
      else if (uri.startsWith("/cassandra/")) downloadFile(Config.cassandra, response)
      else if (Config.jre != null && uri.startsWith("/jre/")) downloadFile(Config.jre, response)
      else if (uri.startsWith("/api/node")) handleNodeApi(request, response)
      else if (uri.startsWith("/api/cluster")) handleClusterApi(request, response)
      else response.sendError(404)
    }

    def downloadFile(file: File, response: HttpServletResponse) {
      response.setContentType("application/zip")
      response.setHeader("Content-Length", "" + file.length())
      response.setHeader("Content-Disposition", "attachment; filename=\"" + file.getName + "\"")
      Util.IO.copyAndClose(new FileInputStream(file), response.getOutputStream)
    }
    
    def handleNodeApi(request: HttpServletRequest, response: HttpServletResponse) {
      response.setContentType("application/json; charset=utf-8")
      var uri: String = request.getRequestURI.substring("/api/node".length)
      if (uri.startsWith("/")) uri = uri.substring(1)

      if (uri == "list") handleListNodes(request, response)
      else if (uri == "add" || uri == "update") handleAddUpdateNode(uri == "add", request, response)
      else if (uri == "remove") handleRemoveNode(request, response)
      else if (uri == "start" || uri == "stop") handleStartStopNode(uri == "start", request, response)
      else response.sendError(404)
    }
    
    def handleClusterApi(request: HttpServletRequest, response: HttpServletResponse) {
      response.setContentType("application/json; charset=utf-8")
      var uri: String = request.getRequestURI.substring("/api/cluster".length)
      if (uri.startsWith("/")) uri = uri.substring(1)

      if (uri == "list") handleListClusters(request, response)
      else if (uri == "add" || uri == "update") handleAddUpdateCluster(uri == "add", request, response)
      else if (uri == "remove") handleRemoveCluster(request, response)
      else response.sendError(404)
    }

    def handleListNodes(request: HttpServletRequest, response: HttpServletResponse) {
      val nodesJson = Nodes.getNodes.map(_.toJson(expanded = true))
      response.getWriter.println("" + new JSONArray(nodesJson.toList))
    }

    def handleAddUpdateNode(add: Boolean, request: HttpServletRequest, response: HttpServletResponse) {
      val expr: String = request.getParameter("node")
      if (expr == null || expr.isEmpty) throw new HttpError(400, "node required")

      var ids: List[String] = null
      try { ids = Expr.expandNodes(expr) }
      catch { case e: IllegalArgumentException => throw new HttpError(400, "invalid node expr") }

      var cluster: Cluster = null
      if (request.getParameter("cluster") != null) {
        cluster = Nodes.getCluster(request.getParameter("cluster"))
        if (cluster == null) throw new HttpError(400, "cluster not found")
      }

      var cpu: java.lang.Double = null
      if (request.getParameter("cpu") != null)
        try { cpu = java.lang.Double.valueOf(request.getParameter("cpu")) }
        catch { case e: NumberFormatException => throw new HttpError(400, "invalid cpu") }

      var mem: java.lang.Long = null
      if (request.getParameter("mem") != null)
        try { mem = java.lang.Long.valueOf(request.getParameter("mem")) }
        catch { case e: NumberFormatException => throw new HttpError(400, "invalid mem") }

      val broadcast: String = request.getParameter("broadcast")

      var stickinessPeriod: Period = null
      if (request.getParameter("stickinessPeriod") != null)
        try { stickinessPeriod = new Period(request.getParameter("stickinessPeriod")) }
        catch { case e: IllegalArgumentException => throw new HttpError(400, "Invalid stickinessPeriod") }

      val rack: String = request.getParameter("rack")
      val dc: String = request.getParameter("dc")

      var constraints: Map[String, List[Constraint]] = null
      if (request.getParameter("constraints") != null) {
        try { constraints = Constraint.parse(request.getParameter("constraints")) }
        catch { case e: IllegalArgumentException => throw new HttpError(400, "invalid constraints") }
      }

      var seedConstraints: Map[String, List[Constraint]] = null
      if (request.getParameter("seedConstraints") != null) {
        try { seedConstraints = Constraint.parse(request.getParameter("seedConstraints")) }
        catch { case e: IllegalArgumentException => throw new HttpError(400, "invalid seedConstraints") }
      }

      var seed: java.lang.Boolean = null
      if (request.getParameter("seed") != null) seed = "true" == request.getParameter("seed")
      val replaceAddress: String = request.getParameter("replaceAddress")
      val jvmOptions: String = request.getParameter("jvmOptions")

      val dataFileDirs = request.getParameter("dataFileDirs")
      val commitLogDir = request.getParameter("commitLogDir")
      val savedCachesDir = request.getParameter("savedCachesDir")

      // collect nodes and check existence & state
      val nodes = new ListBuffer[Node]()
      for (id <- ids) {
        val node = Nodes.getNode(id)
        if (add && node != null) throw new HttpError(400, s"node $id exists")
        if (!add && node == null) throw new HttpError(400, s"node $id not exists")
        if (!add && node.state != Node.State.IDLE) throw new HttpError(400, s"node should be idle")
        nodes += (if (add) new Node(id) else node)
      }

      // add|update nodes
      def updateNode(node: Node) {
        if (cluster != null) node.cluster = cluster

        if (cpu != null) node.cpu = cpu
        if (mem != null) node.mem = mem
        if (broadcast != null) node.broadcast = if (broadcast != "") broadcast else null
        if (stickinessPeriod != null) node.stickiness.period = stickinessPeriod

        if (rack != null) node.rack = if (rack != "") rack else "default"
        if (dc != null) node.dc = if (dc != "") dc else "default"

        if (constraints != null) {
          node.constraints.clear()
          node.constraints ++= constraints
        }
        if (seedConstraints != null) {
          node.seedConstraints.clear()
          node.seedConstraints ++= seedConstraints
        }

        if (seed != null) node.seed = seed
        if (replaceAddress != null) node.replaceAddress = if (replaceAddress != "") replaceAddress else null
        if (jvmOptions != null) node.jvmOptions = if (jvmOptions != "") jvmOptions else null

        if (dataFileDirs != null) node.dataFileDirs = if (dataFileDirs != "") dataFileDirs else null
        if (commitLogDir != null) node.commitLogDir = if (commitLogDir != "") commitLogDir else null
        if (savedCachesDir != null) node.savedCachesDir = if (savedCachesDir != "") savedCachesDir else null
      }

      for (node <- nodes) {
        updateNode(node)
        if (add) Nodes.addNode(node)
      }
      Nodes.save()

      // return result
      val nodesJson = nodes.map(_.toJson(expanded = true))
      response.getWriter.println("" + new JSONArray(nodesJson.toList))
    }

    def handleRemoveNode(request: HttpServletRequest, response: HttpServletResponse) {
      val expr: String = request.getParameter("node")
      if (expr == null || expr.isEmpty) throw new HttpError(400, "node required")

      var ids: List[String] = null
      try { ids = Expr.expandNodes(expr) }
      catch { case e: IllegalArgumentException => throw new HttpError(400, "invalid node expr") }

      val nodes = new ListBuffer[Node]
      for (id <- ids) {
        val node: Node = Nodes.getNode(id)
        if (node == null) throw new HttpError(400, s"node $id not found")
        if (node.state != Node.State.IDLE) throw new HttpError(400, s"node $id should be idle")
        nodes += node
      }

      nodes.foreach(Nodes.removeNode)
      Nodes.save()
    }

    def handleStartStopNode(start: Boolean, request: HttpServletRequest, response: HttpServletResponse) {
      val expr: String = request.getParameter("node")
      if (expr == null || expr.isEmpty) throw new HttpError(400, "node required")

      var ids: List[String] = null
      try { ids = Expr.expandNodes(expr) }
      catch { case e: IllegalArgumentException => throw new HttpError(400, "invalid node expr") }

      var timeout = Duration("2 minutes")
      if (request.getParameter("timeout") != null) {
        try { timeout = Duration(request.getParameter("timeout")) }
        catch { case e: IllegalArgumentException => throw new HttpError(400, "invalid timeout") }
      }

      // check&collect nodes
      val nodes = new ListBuffer[Node]
      for (id <- ids) {
        val node = Nodes.getNode(id)
        if (node == null) throw new HttpError(400, s"node $id not found")
        if (start && node.state != Node.State.IDLE) throw new HttpError(400, s"node $id should be idle")
        if (!start && node.state == Node.State.IDLE) throw new HttpError(400, s"node $id is idle")
        nodes += node
      }

      // start|stop nodes
      for (node <- nodes) {
        if (start) node.state = Node.State.STARTING
        else Scheduler.stopNode(node.id)
      }
      Nodes.save()

      var success: Boolean = true
      if (timeout.toMillis > 0) {
        val targetState: State.Value = if (start) Node.State.RUNNING else Node.State.IDLE
        success = nodes.forall(_.waitFor(targetState, timeout))
      }

      val nodesJson = nodes.map(_.toJson(expanded = true))

      def status: String = {
        if (timeout.toMillis == 0) return "scheduled"
        if (!success) return "timeout"
        if (start) "started" else "stopped"
      }

      val resultJson = new JSONObject(Map("status" -> status, "nodes" -> new JSONArray(nodesJson.toList)))
      response.getWriter.println("" + resultJson)
    }

    private def handleListClusters(request: HttpServletRequest, response: HttpServletResponse) {
      val clustersJson = Nodes.getClusters.map(_.toJson)
      response.getWriter.println("" + new JSONArray(clustersJson))
    }
    
    private def handleAddUpdateCluster(add: Boolean, request: HttpServletRequest, response: HttpServletResponse) {
      val id: String = request.getParameter("cluster")
      if (id == null || id.isEmpty) throw new HttpError(400, "cluster required")

      val name: String = request.getParameter("name")

      val internalPort: String = request.getParameter("internalPort")
      if (internalPort != null && !internalPort.isEmpty)
        try { new Util.Range(internalPort) }
        catch { case e: IllegalArgumentException => throw new HttpError(400, "Invalid internalPort") }

      val jmxPort: String = request.getParameter("jmxPort")
      if (jmxPort != null && !jmxPort.isEmpty)
        try { new Util.Range(jmxPort) }
        catch { case e: IllegalArgumentException => throw new HttpError(400, "Invalid jmxPort") }

      val cqlPort: String = request.getParameter("cqlPort")
      if (cqlPort != null && !cqlPort.isEmpty)
        try { new Util.Range(cqlPort) }
        catch { case e: IllegalArgumentException => throw new HttpError(400, "Invalid cqlPort") }

      val thriftPort: String = request.getParameter("thriftPort")
      if (thriftPort != null && !thriftPort.isEmpty)
        try { new Util.Range(thriftPort) }
        catch { case e: IllegalArgumentException => throw new HttpError(400, "Invalid thriftPort") }


      var cluster = Nodes.getCluster(id)
      if (add && cluster != null) throw new HttpError(400, "duplicate cluster")
      if (!add && cluster == null) throw new HttpError(400, "cluster not found")
      if (!add && cluster.active) throw new HttpError(400, "cluster has active nodes")

      if (add)
        cluster = Nodes.addCluster(new Cluster(id))

      if (name != null) cluster.name = if (name != "") name else null

      if (internalPort != null) cluster.ports("internal") = if (internalPort != "") new Util.Range(internalPort) else null
      if (jmxPort != null) cluster.ports("jmx") = if (jmxPort != "") new Util.Range(jmxPort) else null
      if (cqlPort != null) cluster.ports("cql") = if (cqlPort != "") new Util.Range(cqlPort) else null
      if (thriftPort != null) cluster.ports("thrift") = if (thriftPort != "") new Util.Range(thriftPort) else null

      Nodes.save()
      response.getWriter.println(cluster.toJson)
    }

    private def handleRemoveCluster(request: HttpServletRequest, response: HttpServletResponse) {
      val id: String = request.getParameter("cluster")
      if (id == null || id.isEmpty) throw new HttpError(400, "cluster required")

      val cluster = Nodes.getCluster(id)
      if (cluster == null) throw new HttpError(400, "cluster not found")
      if (cluster == Nodes.defaultCluster) throw new HttpError(400, "can't remove default cluster")

      Nodes.removeCluster(cluster)
      Nodes.save()
    }

    private def handleHealth(response: HttpServletResponse) {
      response.setContentType("text/plain; charset=utf-8")
      response.getWriter.println("ok")
    }

    class HttpError(code: Int, message: String) extends Exception(message) {
      def getCode: Int = code
    }
  }
}
