package net.elodina.mesos.dse

import net.elodina.mesos.dse.Util.{Range, BindAddress, parseMap}
import org.junit.{Before, Test, After}
import org.junit.Assert._
import net.elodina.mesos.dse.Cli.sendRequest
import java.io._


class HttpServerTest extends MesosTestCase {
  @Before
  override def before = {
    super.before
    HttpServer.start()
    Nodes.reset()
  }

  @After
  override def after = {
    super.after
    HttpServer.stop()
    Nodes.reset()
  }

  val getPlainResponse = sendRequest(_: String, parseMap(""), urlPathPrefix = "", parseJson = false)
  val getJsonResponse = sendRequest(_: String, _: Map[String, String])

  def assertErrorResponse(req: => Any, code: Int, msg: String): Unit = {
    try{
      req
    } catch {
      case e: IOException => assertEquals(e.getMessage, s"$code - $msg")
    }
  }

  private def makeTempFile(filename: String) = {
    val file = new File(filename)
    val content = "content"
    val tmpWriter = new PrintWriter(file)
    tmpWriter.write(content)
    tmpWriter.flush()
    tmpWriter.close()

    (file, content)
  }

  @Test
  def health() = {
    val response = getPlainResponse("health")
    assertEquals(response, "ok")
  }

  @Test
  def downloadFileEndpoints() = {
    val fileEndpoints = List("jar", "dse", "cassandra", "jre")

    for(fileEndpoint <- fileEndpoints) {
      val (file, content) = makeTempFile(s"tmp_${fileEndpoint}_file.jar")
      Config.jar = file
      Config.dse = file
      Config.cassandra = file
      Config.jre = file
      val response = getPlainResponse(s"$fileEndpoint/")
      file.delete()

      assertEquals(content, response)
    }
  }

  @Test
  def nodeApiList() = {
    val nodeId = "0"
    val node = new Node(nodeId)
    Nodes.addNode(node)
    val response = getJsonResponse("/node/list", Map()).asInstanceOf[List[Map[String, Object]]]
    assertEquals(response.size, 1)
  }

  @Test
  def nodeApiAddUpdate() = {
    type JsonNodes = List[Map[String, Object]]
    val nodeAdd = getJsonResponse("/node/add", _: Map[String, String])

    assertErrorResponse(nodeAdd(Map("node" -> "")), 400, "node required")

    // check simple use case, when only node id is given
    {
      val response = nodeAdd(Map("node" -> "0"))
      assertEquals(Nodes.getNodes.size, 1)
      assertEquals(response.asInstanceOf[JsonNodes].head("id"), Nodes.getNode("0").id)
    }

    // check all possible parameters, except 'cluster'
    {
      val parameters = Map(
        "node" -> "1",
        "cpu" -> "1",
        "mem" -> "1",
        "stickinessPeriod" -> "30m",
        "rack" -> "rack",
        "dc" -> "dc",
        "constraints" -> "",
        "seedConstraints" -> "",
        "seed" -> "0.0.0.0",
        "jvmOptions" -> "-Dfile.encoding=UTF8",
        "dataFileDirs" -> "/tmp/datadir",
        "commitLogDir" -> "/tmp/commitlog",
        "savedCachesDir" -> "/tmp/caches",
        "cassandraDotYaml" -> "num_tokens=312",
        "cassandraJvmOptions" -> "cassandra.ring_delay=15000"
      )

      Nodes.reset()
      val response = nodeAdd(parameters).asInstanceOf[JsonNodes].head
      val node = Nodes.getNode("1")
      assertEquals(Nodes.getNodes.size, 1)
      assertNodeEquals(node, new Node(response, expanded = true))
    }
  }

  @Test
  def nodeApiRemove() = {
    val nodeRemove = getJsonResponse("/node/remove", _: Map[String, String])

    assertErrorResponse(nodeRemove(Map("node" -> "")), 400, "node required")
    assertErrorResponse(nodeRemove(Map("node" -> "+")), 400, "invalid node expr")
    assertErrorResponse(nodeRemove(Map("node" -> "1")), 400, "node 1 not found")

    val id = "1"
    val node = new Node(id)
    node.state = Node.State.RUNNING
    Nodes.addNode(node)
    Nodes.save()

    assertErrorResponse(nodeRemove(Map("node" -> "1")), 400, s"node $id should be idle")

    node.state = Node.State.IDLE
    nodeRemove(Map("node" -> "1"))
    assertEquals(Nodes.getNodes.size, 0)
  }

  @Test
  def nodeApiStart() = {
    val nodeStart = getJsonResponse("/node/start", _: Map[String, String])

    assertErrorResponse(nodeStart(Map("node" -> "")), 400, "node required")
    assertErrorResponse(nodeStart(Map("node" -> "+")), 400, "invalid node expr")
    assertErrorResponse(nodeStart(Map("node" -> "1")), 400, "node 1 not found")

    val id = "1"
    val node = new Node(id)
    Nodes.addNode(node)
    Nodes.save()

    assertErrorResponse(nodeStart(Map("node" -> id, "timeout" -> "+")), 400, "invalid timeout")

    val response = nodeStart(Map("node" -> id, "timeout" -> "0 ms")).asInstanceOf[Map[String, Any]]

    assertTrue(Nodes.getNodes.forall(_.state == Node.State.STARTING))
    assertEquals(response("nodes").asInstanceOf[List[Any]].size, 1)
  }

  @Test
  def clusterApiList() = {
    val response = getJsonResponse("/cluster/list", Map())

    assertEquals(List(Map("id" -> "default", "ports" -> Map())),
      response.asInstanceOf[List[Map[String, Any]]])
  }

  @Test
  def clusterApiAdd() = {
    def removeCluster(id: String) = {
      Nodes.removeCluster(Nodes.getCluster(id))
      Nodes.save()
    }

    val addCluster = getJsonResponse("/cluster/add", _: Map[String, String]).asInstanceOf[Map[String, Any]]
    val clusterId = "test cluster"

    assertErrorResponse(addCluster(Map()), 400, "cluster required")

    {
      val response = addCluster(Map("cluster" -> clusterId))
      assertEquals(Map("id" -> clusterId, "ports" -> Map()), response)
    }

    // bind address

    // FIXME: actual response is {"id" : "test cluster", "bindAddress" : "+", "ports" : {}}, endpoint doesn't return 400 error
    // in case when bind address is wrong
    // FIXME: uncomment next verification when bind address checking will be fixed
    // removeCluster(clusterId)
    // val wrongBindAddress = "+"
    // assertErrorResponse(addCluster(Map("bindAddress" -> wrongBindAddress)), 400, "invalid bindAddress")

    val correctBindAddress = "0.0.0.0"
    removeCluster(clusterId)
    assertEquals(
      Map("id" -> clusterId, "bindAddress" -> correctBindAddress, "ports" -> Map()),
      addCluster(Map("cluster" -> clusterId, "bindAddress" -> correctBindAddress)))

    // map of tested ports, format:
    // Map(portName -> Tuple3(propertyName, wrongValue, correctValue))
    val testedPorts = Map(
      "storagePort" -> ("storage", "+", "10000..11000"),
      "jmxPort" -> ("jmx", "+", "11111"),
      "cqlPort" -> ("cql", "+", "11111"),
      "thriftPort" -> ("thrift", "+", "11111"),
      "agentPort" -> ("agent", "+", "11111")
    )

    for((portName, (propName, wrong, correct)) <- testedPorts) {
      removeCluster(clusterId)
      assertErrorResponse(
        addCluster(Map(portName -> wrong, "cluster" -> clusterId)), 400, s"invalid $portName")

      assertEquals(
        Map("id" -> clusterId, "ports" -> Map(propName -> correct)),
        addCluster(Map(portName -> correct, "cluster" -> clusterId)))
    }

    assertErrorResponse(addCluster(Map("cluster" -> clusterId)), 400, "duplicate cluster")
  }

  @Test
  def clusterApiUpdate() = {
    val updateCluster = getJsonResponse("/cluster/update", _: Map[String, String])
    val clusterId = "test cluster"
    val cluster = new Cluster(clusterId)
    val (bindAddress, storagePort, jmxPort, cqlPort, thriftPort, agentPort) = ("0.0.0.0", "1111", "1111", "1111", "1111", "1111")
    cluster.bindAddress = new BindAddress(bindAddress)
    cluster.ports(Node.Port.STORAGE) = new Range(storagePort)
    cluster.ports(Node.Port.JMX) = new Range(jmxPort)
    cluster.ports(Node.Port.CQL) = new Range(cqlPort)
    cluster.ports(Node.Port.THRIFT) = new Range(thriftPort)
    cluster.ports(Node.Port.AGENT) = new Range(agentPort)

    Nodes.addCluster(cluster)
    Nodes.save()

    // bind address
    val newBindAddress = "127.0.0.1"
    updateCluster(Map("bindAddress" -> newBindAddress, "cluster" -> clusterId))
    assertEquals(cluster.bindAddress.toString, newBindAddress)

    val ports = Map(
      "storagePort" -> (Node.Port.STORAGE, "2222"),
      "jmxPort" -> (Node.Port.JMX, "2222"),
      "cqlPort" -> (Node.Port.CQL, "2222"),
      "thriftPort"  ->(Node.Port.THRIFT, "2222"),
      "agentPort" -> (Node.Port.AGENT, "2222")
    )

    for ((portName, (portType, newPortValue)) <- ports) {
      updateCluster(Map(portName -> newPortValue, "cluster" -> clusterId))
      assertEquals(cluster.ports(portType).toString, newPortValue)
    }
  }

  @Test
  def clusterApiRemove() = {
    val removeCluster = getJsonResponse("/cluster/remove", _: Map[String, String])

    assertErrorResponse(removeCluster(Map()), 400, "cluster required")
    assertErrorResponse(removeCluster(Map("cluster" -> "noneExistent")), 400, "cluster not found")
    assertErrorResponse(removeCluster(Map("cluster" -> "default")), 400, "can't remove default cluster")

    val cluster = new Cluster("test cluster")
    val node = new Node("0")
    node.cluster = cluster
    node.state = Node.State.RUNNING
    Nodes.addCluster(cluster)
    Nodes.addNode(node)
    Nodes.save()

    assertErrorResponse(removeCluster(Map("cluster" -> cluster.id)), 400, "can't remove cluster with active nodes")

    node.state = Node.State.IDLE
    Nodes.save()
    removeCluster(Map("cluster" -> cluster.id))
    assertEquals(Nodes.getCluster(cluster.id), null)
  }
}