package com.tailf.packages.ned.nexus;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.http.NoHttpResponseException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.tailf.ned.NedErrorCode;
import com.tailf.ned.NedException;
import com.tailf.ned.NedWorker;
import com.tailf.packages.ned.nedcom.NedSettings;
import com.tailf.packages.ned.nedcom.NedCommonLib.NedState;
import com.tailf.packages.ned.nedcom.NedProgress;
import static com.tailf.packages.ned.nedcom.NedProgress.reportProgressStart;
import static com.tailf.packages.ned.nedcom.NedProgress.reportProgressStop;

/**
 * Implements the NXAPI interface for the Cisco Nexus NED
 * @author jrendel
 *
 */
public class NexusNXAPIDevice extends NexusDevice {

    String address;
    private int lastSuccessCount = 0;
    private NexusNXAPIConnection connection;

    public NexusNXAPIDevice(NexusNedCli ned) throws Exception {
        super(ned);
        connection = new NexusNXAPIConnection(ned, "/ins/");
    }

    @Override
    public void
    connect(NedWorker worker, NedSettings nedSettings) throws NedException {
        ned.logDebug(worker, "NXAPI CONNECT ==>");
        try {
            connection.connect(worker);
        } catch (Exception e) {
            throw new NedException(NedErrorCode.CONNECT_CONNECTION_REFUSED, e.getMessage());
        }
        ned.logDebug(worker, "NXAPI CONNECT OK");
    }

    @Override
    public String setup(NedWorker worker, NedSettings nedSettings) throws Exception {
        ned.logDebug(worker, "NXAPI SETUP ==>");
        String version = sendNXAPIRequest(worker, "cli_show_ascii", "show version", true);
        ned.logDebug(worker, "NXAPI SETUP OK");
        return version;
    }

    @Override
    public boolean isAlive() {
        // Let the httpClient do its Keep-Alive stuff and let NCS
        // believe that the connection is always up
        // NOTE: Here we make sure stale connections are removed before trying to use the http client
        // (needed according to: https://hc.apache.org/httpcomponents-client-ga/tutorial/html/connmgmt.html)
        connection.flush();
        return true;
    }

    private static int getIndentLevel(String line) {
        int indentLevel = 0;
        for (int i = 0; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i))) {
                indentLevel += 1;
            } else {
                break;
            }
        }
        return indentLevel;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void
    applyConfig(NedWorker worker, int cmd, String data) throws NedException {
        NexusNedCli.log.debug("NXAPI APPLYCONFIG ==>");
        worker.setTimeout(ned.writeTimeout);

        /*
         * Prepare command sequence.
         * Run filtering, reformatting and trimming on it.
         */
        String[] lines = ned.modifyData(worker, data, NedState.APPLY);
        List<String> linesToSend = new ArrayList<>();
        List<Integer> lineIndents = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = ned.modifyLine(worker, lines[i].trim());
            if (line == null || line.equals(""))
                continue;
            linesToSend.add(line+"\n");
            lineIndents.add(getIndentLevel(lines[i]));

            ned.logVerbose(worker, String.format("(%d) %s", lineIndents.get(lineIndents.size()-1), line));
        }

        if (linesToSend.isEmpty()) {
            ned.logInfo(worker, "Got empty diff-set, applyConfig() doing nothing");
            return;
        }

        long retryDelay = ned.deviceRetryDelay;
        int retryCount = 0;
        boolean doRetry = false;
        // Throws after ned.deviceRetryCount retries
        NedProgress.Progress progress = reportProgressStart(ned, "sending config");
        try {
            do {
                StringBuilder newdata = new StringBuilder();
                for (String line : linesToSend) {
                    newdata.append(line);
                }

                data = newdata.toString();
                data = data.replace("\n", " ;");

                // Throw on error if retryCount == 0
                String cliError = sendNXAPIRequest(worker, "cli_conf", data, (retryCount == ned.deviceRetryCount));

                if (lastSuccessCount < linesToSend.size()) {
                    String failLine = linesToSend.get(lastSuccessCount);
                    String msg = String.format("Command failed : '%s' with error: '%s'",
                                               failLine.trim(),
                                               (cliError != null) ? String.format("(%s)", cliError.trim()) : "unknown");
                    ned.logError(worker, msg, new Exception(""));
                    if (!isCliRetry(cliError)) {
                        throw new NedException(msg);
                    }
                    if (retryCount == 0) {
                        // Reset timeout > max timeout
                        worker.setTimeout(ned.deviceRetryCount*ned.deviceRetryDelay);
                    }
                    retryCount += 1;
                    ned.logInfo(worker, String.format("Retry from command '%s'", failLine));
                    LinkedList<String> linesToRetry = new LinkedList<>();
                    LinkedList<Integer> lineIndentsRetry = new LinkedList<>();
                    int currentLevel = lineIndents.get(lastSuccessCount);
                    int currentLine = lastSuccessCount;
                    while ((currentLine >= 0) && (currentLevel > 0)) {
                        if (lineIndents.get(currentLine) < currentLevel) {
                            currentLevel = lineIndents.get(currentLine);
                            linesToRetry.addFirst(linesToSend.get(currentLine));
                            lineIndentsRetry.addFirst(lineIndents.get(currentLine));
                        }
                        currentLine--;
                    }
                    linesToRetry.addAll(linesToSend.subList(lastSuccessCount, linesToSend.size()));
                    lineIndentsRetry.addAll(lineIndents.subList(lastSuccessCount, lineIndents.size()));
                    linesToSend = linesToRetry;
                    lineIndents = lineIndentsRetry;
                    doRetry = true;
                    sleep(worker, retryDelay, true);
                } else {
                    doRetry = false;
                }
            } while (doRetry);
            reportProgressStop(progress);
        } catch (Exception e) {
            reportProgressStop(progress, "error");
            throw e;
        }

        NexusNedCli.log.debug("NXAPI APPLYCONFIG OK");
    }


    @Override
    public void
    saveConfig(NedWorker worker) throws NedException {
        NexusNedCli.log.debug("NXAPI SAVECONFIG ==>");
        String cmd = "copy running-config startup-config";
        sendNXAPIRequest(worker, "cli_conf", cmd, true);
        NexusNedCli.log.debug("NXAPI SAVECONFIG OK");
    }


    @Override
    public void
    close() throws NedException, IOException {
        NexusNedCli.log.debug("NXAPI CLOSE ==>");
        try {
            connection.close();
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
        NexusNedCli.log.debug("NXAPI CLOSE OK");
    }


    @Override
    public String
    getConfig(NedWorker worker) throws NedException {
        NexusNedCli.log.debug("NXAPI GETCONFIG ==>");
        worker.setTimeout(ned.readTimeout);
        boolean showIfaceAll = ned.hasBehaviour("show-interface-all");
        boolean showCMapsAll = ned.hasBehaviour("show-class-map-all");

        String cmd = "show running-config";
        String ret = sendNXAPIRequest(worker, "cli_show_ascii", cmd, true);

        if (ret.contains("vpc domain "))
        {
            String vpcConfig = sendNXAPIRequest(worker, "cli_show_ascii", "show running-config vpc all", true);
            vpcConfig = "! PARSER_EXIT_TO_TOP\n" + vpcConfig;
            ret += vpcConfig;
        }

        if (showIfaceAll) {
            String showInterfaceAllCmd = ned.showInterfaceAllCmd;
            if (showInterfaceAllCmd == null) {
                showInterfaceAllCmd = "show running-config interface all";
            }

            String ifaceAll = sendNXAPIRequest(worker, "cli_show_ascii", showInterfaceAllCmd, true);
            String[] lines = ifaceAll.split("\n");
            StringBuilder cleanedIfaceAll = new StringBuilder();
            String incPat = ned.vtsPrivateFeatures ?
                                                    "^\\s*(interface .*|(no )?shutdown)$" :
                                                        "^\\s*(interface .*|(no )?shutdown|(no )?switchport|(flowcontrol (receive|send) (on|off)))$";

            cleanedIfaceAll.append("! PARSER_EXIT_TO_TOP\n");

            for (String l : lines) {
                if (l.matches(incPat)) {
                    cleanedIfaceAll.append(l);
                    cleanedIfaceAll.append("\n");
                }
            }
            ret += cleanedIfaceAll.toString();
        }

        if (showCMapsAll) {
            String showAllClassMaps = "show class-map type network-qos ; show class-map type queuing ; show class-map type control-plane ; show class-map type qos";
            String cmapAll = sendNXAPIRequest(worker, "cli_show_ascii", showAllClassMaps, true);
            cmapAll = cmapAll.replaceAll("Class-map", "class-map");
            cmapAll = cmapAll.replaceAll("Description:", "description");
            cmapAll = cmapAll.replaceAll("Type \\S+ class-maps", "");
            cmapAll = cmapAll.replaceAll("^\\s+=+$", "");
            ret += cmapAll;
        }

        NexusNedCli.log.debug("NXAPI GETCONFIG OK");

        return ret;
    }

    @Override
    public String
    command(NedWorker worker, String cmd, CommandType cmdType, String[] answers) throws Exception {
        return this.command(worker, cmd, cmdType != CommandType.EXEC_CMD);
    }

    @Override
    public String
    command(NedWorker worker, String cmd, boolean config) throws Exception {
        NexusNedCli.log.debug("NXAPI COMMAND ==>");
        worker.setTimeout(ned.readTimeout);
        String type = (config ? "cli_conf" : "cli_show_ascii");
        String ret = sendNXAPIRequest(worker, type, cmd, true);
        NexusNedCli.log.debug("NXAPI COMMAND OK");

        return ret;
    }

    /**
     * Send a NXAPI request message, i.e. a REST-XML POST message.
     * @param worker - the ned worker context
     * @param type   - command type, cli_conf or show_ascii
     * @param cmd    - command to send to device
     *
     * @return a string containing the output returned from the device.
     * @throws NedException
     */
    @SuppressWarnings({"deprecation","squid:S3457"})
    private String sendNXAPIRequest(NedWorker worker, String type, String cmd, boolean throwOnError)
        throws NedException {

        String err = "NXAPI failure :: ";

        try {
            DocumentBuilderFactory docFactory =
                DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

            Document doc = docBuilder.newDocument();
            Node root = doc.createElement("ins_api");
            doc.appendChild(root);

            Node node = doc.createElement("version");
            node.setTextContent("1.0");
            root.appendChild(node);

            node = doc.createElement("type");
            node.setTextContent(type);
            root.appendChild(node);

            node = doc.createElement("chunk");
            node.setTextContent("0");
            root.appendChild(node);

            node = doc.createElement("sid");
            node.setTextContent("session1");
            root.appendChild(node);

            node = doc.createElement("input");
            node.setTextContent(cmd);
            root.appendChild(node);

            node = doc.createElement("output_format");
            node.setTextContent("xml");
            root.appendChild(node);

            TransformerFactory transformerFactory =
                TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            StringWriter writer = new StringWriter();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.transform(new DOMSource(doc), new StreamResult(writer));

            String xml = writer.getBuffer().toString();
            String ret = doPost(worker, xml);

            InputSource is = new InputSource();
            is.setCharacterStream(new StringReader(ret));

            doc = docBuilder.parse(is);
            doc.getDocumentElement().normalize();

            NodeList outputs = doc.getElementsByTagName("output");

            if (outputs == null) {
                throw new NedException(err + "no outputs in resp");
            }

            /*
             *  The NXAPI response can contain a long list of return codes.
             *  Need to check each one of them to make sure the operation
             *  was successful.
             */
            lastSuccessCount = 0;
            for (int i = 0; i < outputs.getLength(); i++) {
                Node o = outputs.item(i);
                String msg = null;
                String code = null;
                String clierror = null;
                String body = "";

                for (Node n = o.getFirstChild();
                    n != null;
                    n = n.getNextSibling()) {

                    if (n.getNodeName().equals("msg")) {
                        msg = n.getTextContent();
                    }
                    if (n.getNodeName().equals("code")) {
                        code = n.getTextContent();
                    }
                    if (n.getNodeName().equals("clierror")) {
                        clierror = n.getTextContent();
                    }
                    if (n.getNodeName().equals("body")) {
                        body = n.getTextContent().trim();
                    }
                }

                if (msg == null) {
                    throw new NedException(err + "no msg in response!");
                }

                if (!msg.equals("Success") || ("cli_conf".equals(type) && isCliError(worker, body))) {
                    if (clierror == null) {
                        clierror = body;
                    }
                    if (throwOnError) {
                        throw new NedException(err + "code " + code
                                               + " :: " + clierror);
                    } else {
                        return clierror;
                    }
                }

                lastSuccessCount += 1;
            }

            NodeList body = doc.getElementsByTagName("body");

            if (body == null) {
                throw new NedException(err + " :: no body in response!");
            }

            StringBuilder bodyText = new StringBuilder();
            for (int i = 0; i < body.getLength(); i++) {
                bodyText.append(body.item(i).getTextContent());
            }

            return bodyText.toString();
        }
        catch (Exception e) {
            ned.logError(worker, "NXAPI failure ", e);
            String msg = String.format("NXAPI failure (%s) :: %s\n", e.getClass().getName(), e.getMessage());
            throw new NedException(msg);
        }
    }

    private String doPost(NedWorker worker, String xml) throws Exception {
        String ret = null;
        try {
            ret = connection.post(worker, "", xml);
        } catch (NoHttpResponseException ee) {
            // NOTE: Retry once, since it can not be guaranteed that the
            // server, has not closed the connection since last isAlive()
            // Also, seems closeExpiredConnections() is not reliable anyway
            ned.logDebug(worker, String.format("Http error %s, retry", ee.getMessage()));
            connection.connect(worker);
            ret = connection.post(worker, "", xml);
        }
        return ret;
    }
}
