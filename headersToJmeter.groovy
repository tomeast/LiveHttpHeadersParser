#!/usr/bin/env groovy

import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource

if(this.args.size() < 1) {
    println "Usage: ./headersToJmeter.groovy LIVE_HTTP_HEADER_FILE"
    println "Please specifiy the full path to a Live Http Header log file"
    return
}

def requests = [:]
def currentUrl
def requestBlock = false
new File(args[0]).eachLine() { line ->
    if(!currentUrl) {
        currentUrl = line
        requests[currentUrl] = [:]
        def url = currentUrl.split("://")[1].split("/")[0]
        if(url =~ ":") {
            //port needs to be stripped off
            requests[currentUrl]["domain"] = url.split(":")[0]
            requests[currentUrl]["port"] = url.split(":")[1].split("/")[0]
            requests[currentUrl]["protocol"] = "http"
        } else if(currentUrl =~ "https") {
            requests[currentUrl]["domain"] = url.split("/")[0]
            requests[currentUrl]["port"] = "443"
            requests[currentUrl]["protocol"] = "https"
        } else {
            requests[currentUrl]["domain"] = url.split("/")[0]
            requests[currentUrl]["port"] = "80"
            requests[currentUrl]["protocol"] = "http"
        }

    }
    if(line =~ /----------/) {
        currentUrl = null
    }
    if(line.equals("") || (requestBlock && line.endsWith("OK"))) {
        //transitioning to a different block
        requestBlock = !requestBlock
    } else {
        if(requestBlock) {
            if(line =~ /^GET|POST/) {
                //this is the protocol + path + version
                //ie: GET /Welcome.do HTTP/1.1
                requests[currentUrl]["method"] = line.split(" ")[0]
                requests[currentUrl]["path"] = line.split(" ")[1]
            } else if(line =~ ": ") {
                //these are the request headers
                //ie: Host: myhost.com:8080
                def headers = requests[currentUrl]["headers"]
                if(!headers) {
                    headers = [:]
                    requests[currentUrl]["headers"] = headers
                }
                headers[line.split(": ")[0]] = line.split(": ")[1]
            } else {
                //this is post data
                //ie: username=jjones&password=12345&lt=e1s1&_eventId=submit&submit=LOGIN
                def parameters = requests[currentUrl]["parameters"]
                if(!parameters) {
                    parameters = [:]
                    requests[currentUrl]["parameters"] = parameters
                }
                line.split("&").each() { nameValue ->
                    parameters[nameValue.split("=")[0].trim()] = nameValue.split("=")[1]
                }
            }
        }
    }
}

//now we have all the request information we'll convert it to xml
def requestsXml = new StringWriter()
def builder = new groovy.xml.MarkupBuilder(requestsXml)
builder.requests {
    requests.each() { url, values ->
        request() {
            values.each() { key, value ->
                if(key == "headers") {
                    headers() {
                        value.each() { name, val ->
                            header(name: "${name}", value: "${val}")
                        }
                    }
                } else if(key == "parameters") {
                    parameters() {
                        value.each() { name, val ->
                            parameter(name: "${name}", value: "${val}")
                        }
                    }
                } else {
                    "${key}""${value}"
                }
            }
        }
    }
}      

//an xsl that has the skeleton jmx file
def jmxXsl = '''
<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:xd="http://www.oxygenxml.com/ns/doc/xsl"
    exclude-result-prefixes="xs xd" version="2.0">
    <xsl:template match="/requests">
        <jmeterTestPlan version="1.2" properties="2.1" xmlns="jmx">
            <hashTree>
                <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="Test Plan"
                    enabled="true">
                    <stringProp name="TestPlan.comments"/>
                    <boolProp name="TestPlan.functional_mode">false</boolProp>
                    <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>
                    <elementProp name="TestPlan.user_defined_variables" elementType="Arguments"
                        guiclass="ArgumentsPanel" testclass="Arguments"
                        testname="User Defined Variables" enabled="true">
                        <collectionProp name="Arguments.arguments"/>
                    </elementProp>
                    <stringProp name="TestPlan.user_define_classpath"/>
                </TestPlan>
                <hashTree>
                    <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup"
                        testname="Thread Group" enabled="true">
                        <elementProp name="ThreadGroup.main_controller" elementType="LoopController"
                            guiclass="LoopControlPanel" testclass="LoopController"
                            testname="Loop Controller" enabled="true">
                            <boolProp name="LoopController.continue_forever">false</boolProp>
                            <stringProp name="LoopController.loops">1</stringProp>
                        </elementProp>
                        <stringProp name="ThreadGroup.num_threads">1</stringProp>
                        <stringProp name="ThreadGroup.ramp_time">1</stringProp>
                        <longProp name="ThreadGroup.start_time">1311093433000</longProp>
                        <longProp name="ThreadGroup.end_time">1311093433000</longProp>
                        <boolProp name="ThreadGroup.scheduler">false</boolProp>
                        <stringProp name="ThreadGroup.on_sample_error">continue</stringProp>
                        <stringProp name="ThreadGroup.duration"/>
                        <stringProp name="ThreadGroup.delay"/>
                    </ThreadGroup>
                    <hashTree>
                        <CookieManager guiclass="CookiePanel" testclass="CookieManager" testname="HTTP Cookie Manager" enabled="true">
                            <collectionProp name="CookieManager.cookies"/>
                            <boolProp name="CookieManager.clearEachIteration">false</boolProp>
                            <stringProp name="CookieManager.policy">rfc2109</stringProp>
                        </CookieManager>
                        <hashTree/>
                        <CacheManager guiclass="CacheManagerGui" testclass="CacheManager" testname="HTTP Cache Manager" enabled="true">
                            <boolProp name="clearEachIteration">false</boolProp>
                        </CacheManager>
                        <hashTree/>
                        <ResultCollector guiclass="ViewResultsFullVisualizer"
                            testclass="ResultCollector" testname="View Results Tree" enabled="true">
                            <boolProp name="ResultCollector.error_logging">false</boolProp>
                            <objProp>
                                <name>saveConfig</name>
                                <value class="SampleSaveConfiguration">
                                    <time>true</time>
                                    <latency>true</latency>
                                    <timestamp>true</timestamp>
                                    <success>true</success>
                                    <label>true</label>
                                    <code>true</code>
                                    <message>true</message>
                                    <threadName>true</threadName>
                                    <dataType>true</dataType>
                                    <encoding>false</encoding>
                                    <assertions>true</assertions>
                                    <subresults>true</subresults>
                                    <responseData>false</responseData>
                                    <samplerData>false</samplerData>
                                    <xml>true</xml>
                                    <fieldNames>false</fieldNames>
                                    <responseHeaders>false</responseHeaders>
                                    <requestHeaders>false</requestHeaders>
                                    <responseDataOnError>false</responseDataOnError>
                                    <saveAssertionResultsFailureMessage>false</saveAssertionResultsFailureMessage>
                                    <assertionsResultsToSave>0</assertionsResultsToSave>
                                    <bytes>true</bytes>
                                </value>
                            </objProp>
                            <stringProp name="filename"/>
                        </ResultCollector>
                        <hashTree/>
                        <xsl:for-each select="request">
                            <HTTPSampler guiclass="HttpTestSampleGui" testclass="HTTPSampler"
                                testname="{path}" enabled="true">
                                <elementProp name="HTTPsampler.Arguments" elementType="Arguments"
                                    guiclass="HTTPArgumentsPanel" testclass="Arguments"
                                    enabled="true">
                                    <collectionProp name="Arguments.arguments">
                                        <xsl:for-each select="parameters/parameter">
                                            <elementProp name="{@name}" elementType="HTTPArgument">
                                                <boolProp name="HTTPArgument.always_encode">false</boolProp>
                                                <stringProp name="Argument.value"><xsl:value-of select="@value"/></stringProp>
                                                <stringProp name="Argument.name"><xsl:value-of select="@name"/></stringProp>
                                                <stringProp name="Argument.metadata">=</stringProp>
                                                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                                            </elementProp>
                                        </xsl:for-each>
                                    </collectionProp>
                                </elementProp>
                                <stringProp name="HTTPSampler.domain">
                                    <xsl:value-of select="domain"/>
                                </stringProp>
                                <stringProp name="HTTPSampler.port">
                                    <xsl:value-of select="port"/>
                                </stringProp>
                                <stringProp name="HTTPSampler.connect_timeout"/>
                                <stringProp name="HTTPSampler.response_timeout"/>
                                <stringProp name="HTTPSampler.protocol">
                                    <xsl:value-of select="protocol"/>
                                </stringProp>
                                <stringProp name="HTTPSampler.contentEncoding">utf-8</stringProp>
                                <stringProp name="HTTPSampler.path">
                                    <xsl:value-of select="path"/>
                                </stringProp>
                                <stringProp name="HTTPSampler.method">
                                    <xsl:value-of select="method"/>
                                </stringProp>
                                <boolProp name="HTTPSampler.follow_redirects">true</boolProp>
                                <boolProp name="HTTPSampler.auto_redirects">false</boolProp>
                                <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
                                <boolProp name="HTTPSampler.DO_MULTIPART_POST">false</boolProp>
                                <stringProp name="HTTPSampler.FILE_NAME"/>
                                <stringProp name="HTTPSampler.FILE_FIELD"/>
                                <stringProp name="HTTPSampler.mimetype"/>
                                <boolProp name="HTTPSampler.monitor">false</boolProp>
                                <stringProp name="HTTPSampler.embedded_url_re"/>
                            </HTTPSampler>
                            <hashTree>
                                <HeaderManager guiclass="HeaderPanel" testclass="HeaderManager"
                                    testname="HTTP Header Manager" enabled="true">
                                    <collectionProp name="HeaderManager.headers">
                                        <xsl:for-each select="headers/header">
                                            <elementProp name="{@name}" elementType="Header">
                                                <stringProp name="Header.name">
                                                  <xsl:value-of select="@name"/>
                                                </stringProp>
                                                <stringProp name="Header.value">
                                                  <xsl:value-of select="@value"/>
                                                </stringProp>
                                            </elementProp>
                                        </xsl:for-each>
                                    </collectionProp>
                                </HeaderManager>
                                <hashTree/>
                            </hashTree>
                        </xsl:for-each>
                    </hashTree>
                </hashTree>
            </hashTree>
        </jmeterTestPlan>
    </xsl:template>
</xsl:stylesheet>
'''.trim()

def factory = TransformerFactory.newInstance()
def transformer = factory.newTransformer(new StreamSource(new StringReader(jmxXsl)))
transformer.transform(new StreamSource(new StringReader(requestsXml.toString())), new StreamResult(System.out))
