package com.tailf.packages.ned.nexus;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FilenameFilter;
import java.io.StringWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import com.tailf.conf.Conf;
import com.tailf.conf.ConfBinary;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfException;
import com.tailf.conf.ConfKey;
import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfBinary;
import com.tailf.conf.ConfUInt32;
import com.tailf.conf.ConfUInt64;
import com.tailf.conf.ConfValue;
import com.tailf.conf.ConfXMLParam;
import com.tailf.conf.ConfXMLParamValue;
import com.tailf.maapi.MaapiConfigFlag;
import com.tailf.navu.NavuContainer;
import com.tailf.navu.NavuException;
import com.tailf.navu.NavuContext;
import com.tailf.navu.NavuLeaf;
import com.tailf.navu.NavuList;
import com.tailf.navu.NavuListEntry;
import com.tailf.navu.NavuNode;
import com.tailf.ncs.ns.Ncs;
import com.tailf.ned.NedCmd;
import com.tailf.ned.NedException;
import com.tailf.ned.NedMux;
import com.tailf.ned.NedTTL;
import com.tailf.ned.NedWorker;
import com.tailf.ned.SSHSessionException;
import com.tailf.ned.TelnetSession;

import com.tailf.packages.ned.nedcom.NsoInfo;
import com.tailf.packages.ned.nedcom.NedMetaData;
import com.tailf.packages.ned.nedcom.MaapiUtils;
import com.tailf.packages.ned.nedcom.NavuUtils;
import com.tailf.packages.ned.nedcom.NedSettings;
import com.tailf.packages.ned.nedcom.NedComCliBase;
import com.tailf.packages.ned.nedcom.NedCommonLib.PlatformInfo;
import com.tailf.packages.ned.nedcom.NedCommonLib.NedState;
import com.tailf.packages.ned.nedcom.NedCommonLib.TransIdMethod;
import com.tailf.packages.ned.nedcom.Schema;
import com.tailf.packages.ned.nedcom.XMLUtils;
import com.tailf.packages.ned.nedcom.livestats.NedLiveStatsException;
import com.tailf.packages.ned.nedcom.livestats.NedLiveStatsShowHandler;
import com.tailf.packages.ned.nedcom.livestats.TextFSM;

import static com.tailf.packages.ned.nedcom.NedString.stringQuote;
import static com.tailf.packages.ned.nedcom.NedString.stringDequote;
import static com.tailf.packages.ned.nedcom.NedString.calculateMd5Sum;
import com.tailf.packages.ned.nedcom.NedProgress;
import static com.tailf.packages.ned.nedcom.NedProgress.reportProgressStart;
import static com.tailf.packages.ned.nedcom.NedProgress.reportProgressStop;

/**
 * This class implements NED interface for Cisco Nexus devices.
 *
 */
@SuppressWarnings("deprecation")
public class NexusNedCli extends NedComCliBase {

    static org.apache.log4j.Logger log  = org.apache.log4j.Logger.getLogger(NexusNedCli.class);
    private static Set<String> behaviourBooleans = new HashSet<>();

    static {
        behaviourBooleans.add("default-notification-mac-move");
        behaviourBooleans.add("default-unsupported-transceiver");
        behaviourBooleans.add("default-lacp-suspend-individual");
        behaviourBooleans.add("default-qos-ns-buffer-profile-mesh");
        behaviourBooleans.add("default-copp-profile-strict");
        behaviourBooleans.add("dayzero-copp-profile-strict");
        behaviourBooleans.add("no-logging-event-link-status-default");
    }

    private static TextFSM readTextFSMTemplate(String name) {
        TextFSM textfsm = null;
        try (
             InputStream in = NexusNedCli.class.getResourceAsStream(name);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in));
             ) {
            List<String> lines = new ArrayList<>();
            String line = null;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            textfsm = TextFSM.parse(lines);
        } catch (Exception e) {
            log.error(String.format("Error loading text-fsm template: %s", e.getMessage()));
            // ignore
        }
        return textfsm;
    }

    protected int deviceOutputDelay = 0;
    protected int deviceRetryCount = 60;
    protected int deviceRetryDelay = 1*1000;
    protected List<Map<String,String>> autoPrompts;

    protected float nxosVersion = 7.0f;
    protected boolean useStrictPersistence = true;
    protected String showInterfaceAllCmd = null;
    protected String defaultSwitchport = "false";
    protected String defaultSwitchportShutdown = "false";

    protected boolean vtsPrivateFeatures = false;

    protected boolean needDeviceTransformedValues = false;
    protected int needSnmpTraps = 0;
    protected boolean forceUpdateTransformedValues = false;
    protected boolean cleartextProvisioning = false;

    protected Set<String> transformedValues;
    protected Set<String> caseInsensitiveValues;
    protected Set<String> handledOBUDelete;
    protected Set<String> handledOBUEdit;

    private boolean configCacheEnabled = false;
    private boolean abortOnDiff = false;
    private String traceOnDiff = null;
    private Schema.TreeNode lastCDBContent = null;
    private String lastKnownConfig = null;
    private long lastKnownConfigTime = 0L;
    private long lastKnownConfigTTL = 0L;

    private String offlineData;

    private String nxdevice = "device";
    private NexusDevice device;
    protected Boolean useCLI = true;
    private String debugShowLoadFile = null;
    protected boolean debugPrepare = false;
    private String devProgVerbosity;

    private Map<String, Boolean> behaviours = new HashMap<>();
    protected boolean vrfMemberL3Redeploy = false;

    private TextFSM vtpStatusParser = null;
    private TextFSM ifaceMTUParser = null;
    private TextFSM obflParser = null;

    private String scheduleName = null;
    private String scheduleTime = null;
    private String deviceTransIdCmd = null;
    private boolean splitLiveStatsExecAny = false;
    private boolean detectedLoadBalanceEthernet = false;

    private static Pattern[] liveStatsPrompts = new Pattern[] {
        Pattern.compile("\\A[^\\# ]+#[ ]?$")
    };
    private class NexusLiveStatsShowHandler extends NedLiveStatsShowHandler {
        public NexusLiveStatsShowHandler() throws NedLiveStatsException {
            super(NexusNedCli.this, NexusNedCli.this.session, liveStatsPrompts);
        }
        public String execute(NedWorker worker, String cmd) throws Exception {
            if (isDevice("netsim") && cmd.endsWith("| xml")) {
                cmd = "xml " + cmd.substring(0,cmd.length()-5);
            }
            if (isDevice("netsim") && cmd.contains(" ; ")) {
                cmd = cmd.replaceAll(" ; ", "_");
            }
            String res = super.execute(worker, cmd);
            if (res.contains("Invalid command")) {
                throw new NedLiveStatsException(String.format("Invalid command: '%s'", cmd));
            }
            res = res.replaceAll("]]>]]>", "");
            res = res.trim();

            if (cmd.contains("show ip route vrf all")) {
                res = res.replaceAll("</ROW_path>\\s+<ROW_path>\\s+<clientname>", "<clientname>");
            } else if(cmd.contains("show forwarding vrf ") && (res.isEmpty() || res.contains("ERROR: table does not exist"))) {
                log.debug(String.format("forwarding table for vrf %s empty", cmd.split(" ")[3]));
                res =
                    "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" +
                    "<nf:rpc-reply xmlns:nf=\"urn:ietf:params:xml:ns:netconf:base:1.0\" xmlns=\"http://www.cisco.com/nxos:1.0:ipfib\">" +
                    " <nf:data>" +
                    "  <show>" +
                    "   <forwarding>" +
                    "            <__readonly__>" +
                    "             <TABLE_module>" +
                    "              <ROW_module>" +
                    "               <TABLE_vrf>" +
                    "                <ROW_vrf>" +
                    "                 <TABLE_prefix>" +
                    "                 </TABLE_prefix>" +
                    "                </ROW_vrf>" +
                    "               </TABLE_vrf>" +
                    "              </ROW_module>" +
                    "             </TABLE_module>" +
                    "            </__readonly__>" +
                    "   </forwarding>" +
                    "  </show>" +
                    " </nf:data>" +
                    "</nf:rpc-reply>\n";
            }
            return res;
        }
    }

    public NexusNedCli() {
        super();
    }

    public NexusNedCli(String device_id, NedMux mux, boolean trace, NedWorker worker) throws Exception {
        super(device_id, mux, trace, worker);

        vtsPrivateFeatures = "true".equals(NsoInfo.capabilities.getProperty("vts-private", "false").trim().toLowerCase());
        if (vtsPrivateFeatures) {
            logInfo(worker, "*** Enabling VTS private features");
        }
    }


    /**
     * Connect to device and login (legacy code)
     * @param
     * @throws Exception
     */
    @Override
    protected void connectDevice(NedWorker worker) throws Exception {
        /*
         *  Get configuration for the Cisco Nexus communication method
         *  Two alternatives:
         *  cli - (default)
         *      Use the standard CLI via either SSH or Telnet
         *  nxapi -
         *      Use the NXAPI (REST-XML) interface
         */
        if (!useCLI) {
            device = new NexusNXAPIDevice(this);
            device.connect(worker, nedSettings);
        } else {
            device = new NexusCLIDevice(this);
            connectorConnectDevice(worker);
        }
    }


    /**
     * Setup device
     * @param
     * @return PlatformInfo
     * @throws Exception
     */
    @Override
    protected PlatformInfo setupDevice(NedWorker worker) throws Exception {
        tracer = trace ? worker : null;

        device.updateAbortRegexList(worker, nedSettings.getListEntries("transaction/config-abort-warning"));
        device.updateIgnoreRegexList(worker, nedSettings.getListEntries("transaction/config-ignore-error"));

        /*
         * Setup device
         */
        String version = device.setup(worker, nedSettings);

        /*
         * Extract detailed os version and machine info
         */
        String platformModel = getVersionInfo(version,
                                              "Hardware\\s*(cisco [^\\r\\n]*)");
        String platformVersion = getVersionInfo(version,
                                                "NXOS:\\s*version\\s*(\\S*)");
        if (platformVersion.equals("UNKNOWN")) {
            platformVersion = getVersionInfo(version,
                                             "system:\\s*version\\s*(\\S*)");
        }

        if ("<5.3".equals(nedSettings.getString("behaviours/port-channel-load-balance-ethernet")) &&
            (platformModel.matches("^.*[Nn]exus5[0-9]+.*") ||
             platformModel.matches("^.*[Nn]exus *3[0-9]+.*"))) {
            String portChanLoadBalanceHelp = device.command(worker, "port-channel load-balance ?", true);
            detectedLoadBalanceEthernet = portChanLoadBalanceHelp.trim().startsWith("ethernet");
            if (detectedLoadBalanceEthernet) {
                logDebug(worker, "Detected presence of cmd 'port-channel load-balance ethernet', enabling 'port-channel-load-balance-ethernet'");
            }
        }

        if (platformVersion.equals("UNKNOWN") && platformModel.equals("UNKNOWN")) {
            platformModel = "NETSIM";
        }

        String platformSerial = getVersionInfo(version,
                                               "(?m)^NAME: \"[Cc]hassis\".*[\n\r]+.*SN:\\s+(\\S+)");

        if (platformSerial.equals("UNKNOWN")) {
            platformSerial = getVersionInfo(version,
                                            "Processor Board ID (\\S+)");
        }

        // use platformName = null to fetch from ned-metadata.properties
        return new PlatformInfo(null, platformVersion, platformModel, platformSerial);
    }

    protected void setupInstance(NedWorker worker, PlatformInfo platformInfo) throws Exception {
        String majMin = getVersionInfo(platformInfo.version, "([0-9]+\\.[0-9]+).*");
        if (!majMin.equals("UNKNOWN")) {
            nxosVersion = Float.valueOf(majMin).floatValue();
            if (platformInfo.model.matches(".*?1000V.*")) {
                logInfo(worker, "Found virtual device");
                nxdevice = "virtual";
            } else {
                logInfo(worker, "Found real device");
            }
        } else {
            // Netsim and unknwon map to 7.0 to avoid old syntax
            nxosVersion = 7.0f;
            logInfo(worker, "Found netsim device");
            nxdevice = "netsim";
        }
        reloadBehaviourSettings();
        if (session != null) {
            nedLiveStats.setupCustomShowHandler(new NexusLiveStatsShowHandler());
        }
        if ("always".equals(traceOnDiff) || "commit".equals(traceOnDiff)) {
            readCDBRunning(worker);
        }
        if ((lastKnownConfigTTL > 0L) || abortOnDiff || !"never".equals(traceOnDiff)) {
            readLastKnownConfig(worker);
        }
    }

    protected void readCDBRunning(NedWorker worker) throws Exception {
        EnumSet<MaapiConfigFlag> flags = EnumSet.of(MaapiConfigFlag.XML_FORMAT);
        String path = String.format("/ncs:devices/device{%s}/config", device_id);
        try {
            maapi.getMyUserSession();
        } catch (Exception ignore) {
            maapi.setUserSession(worker.getUsid());
        }
        int th = maapi.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);
        long s = tick(0);
        String xml = MaapiUtils.readConfig(maapi,
                                           th,
                                           flags,
                                           path);
        maapi.finishTrans(th);
        lastCDBContent = XMLUtils.xml2schema(xml, schema, "/config/devices/device/config");
        lastCDBContent.prune(Schema.DefaultHandlingMode.TRIM);
        logDebug(worker, String.format("Loaded (init) CDB -> XML (%d bytes) %s", xml.length(), tickToString(s)));
    }

    @Override
    protected void nedSettingsDidChange(NedWorker worker, Set<String> changedKeys, boolean isConnected) throws Exception {
        if (device != null) {
            device.updateAbortRegexList(worker, nedSettings.getListEntries("transaction/config-abort-warning"));
            device.updateIgnoreRegexList(worker, nedSettings.getListEntries("transaction/config-ignore-error"));
        }
        this.traceOnDiff = nedSettings.getString("transaction/trace-on-diff");
        this.abortOnDiff = nedSettings.getBoolean("transaction/abort-on-diff");
        this.lastKnownConfigTTL = (long)nedSettings.getInt("transaction/config-cache-ttl") * 1000;
        this.configCacheEnabled = (this.lastKnownConfigTTL > 0L);

        this.autoPrompts = nedSettings.getListEntries("auto-prompts");
        this.useCLI = nedSettings.getString("connection/method").contains("cli");
        this.deviceOutputDelay = nedSettings.getInt("connection/device-output-delay");
        this.deviceRetryCount = nedSettings.getInt("connection/device-retry-count");
        this.deviceRetryDelay = nedSettings.getInt("connection/device-retry-delay");
        this.showInterfaceAllCmd = nedSettings.getString("connection/show-interface-all-cmd");
        this.splitLiveStatsExecAny = nedSettings.getBoolean("connection/split-exec-any");

        this.devProgVerbosity = nedSettings.getString(NedSettings.DEVELOPER_PROG_VERBOSITY);
        if (this.devProgVerbosity == null) {
            this.devProgVerbosity = "disabled"; // NSO 5.1 upgrade parachute for missing default values
        }
        this.debugPrepare = nedSettings.getBoolean("developer/debug-prepare");
        this.debugXML = nedSettings.getBoolean("developer/debug-xml");
        this.debugShowLoadFile = nedSettings.getString("developer/load-from-file");
        if (this.debugPrepare) {
            log.info("NOTE: raw prepareDry() enabled, dry-run native on netsim will show device-specific output");
        }
        if (this.debugShowLoadFile != null) {
            log.info("NOTE: raw show() enabled, loading from: '" + this.debugShowLoadFile + "'");
        }

        String model = nedSettings.getString("persistence/model");
        this.useStrictPersistence = "strict".equals(model);
        if ("schedule".equals(model)) {
            this.scheduleName = nedSettings.getString("persistence/schedule/name");
            if (this.scheduleName == null) {
                throw new NedException("Must give schedule name to use for cisco-nx-persistence/model = 'schedule'");
            }
            this.scheduleTime = nedSettings.getString("persistence/schedule/time");
            if (scheduleTime.length() == 1) {
                scheduleTime = "0" + scheduleTime;
            }
        }

        if ("config-hash".equals(nedSettings.getString("transaction/trans-id-method"))) {
            this.transIdMethod = TransIdMethod.FULL_CONFIG;
        } else if ("device-command".equals(nedSettings.getString("transaction/trans-id-method"))) {
            this.transIdMethod = TransIdMethod.DEVICE_CUSTOM;
            this.deviceTransIdCmd = nedSettings.getString("transaction/trans-id-cmd");
        } else {
            this.transIdMethod = TransIdMethod.MODELED_CONFIG;
        }

        String schedOrNever = ((scheduleName != null) ?
                               String.format("SCHEDULE: %s,%s minutes", scheduleName, scheduleTime) :
                               "NEVER");
        String persistence  = this.useStrictPersistence ? "STRICT" : schedOrNever;

        log.info("Persistence model configured to use: " + persistence);
        log.info("Communication method configured to use " + (useCLI ? "CLI" : "NXAPI"));

        if ("explicit".equals(nedSettings.getString("system-interface-defaults/handling"))) {
            defaultSwitchport = nedSettings.getString("system-interface-defaults/explicit/system-default-switchport/switchport");
            defaultSwitchportShutdown = nedSettings.getString("system-interface-defaults/explicit/system-default-switchport/shutdown");
        }

        if (this.deviceOutputDelay > 0) {
            log.info(String.format("using device-output-delay %d ms.", this.deviceOutputDelay));
        }

        reloadBehaviourSettings();

        List<Map<String,String>> injectEntries = nedSettings.getListEntries("transaction/inject-on-enter-exit-mode");
        for (Map<String,String> entry : injectEntries) {
            String path = entry.get("__key__");
            String key = entry.get("key");
            String onEnter = entry.get("on-enter");
            String onExit = entry.get("on-exit");
            Schema.Node node = schema.getNode(path);
            if (node == null) {
                throw new NedException(String.format("Unknown path in schema given to ned-setting inject-on-enter-exit-mode: '%s'", path));
            }
            if ((onEnter == null) && (onExit == null)) {
                throw new NedException(String.format("Must provide at least one of 'on-enter' or 'on-exit' to ned-setting inject-on-enter-exit-mode: '%s'", path));
            }
            synchronized(schema) {
                // operating on schema, shared between all instances
                String uniqueName = String.format("nx:inject-on-enter-exit-mode-%s", device_id, (key != null ? key : ""));
                node.annotations.removeCallback(uniqueName);
                node.annotations.addCallback(new InjectOnEnterExitMode(node, uniqueName, device_id, key, onEnter, onExit));
            }
        }
    }

    static class InjectOnEnterExitMode extends Schema.CallbackMetaData {
        String devId;
        String key;
        InjectOnEnterExitMode(Schema.Node node, String uniqueName, String devId, String key, String onEnter, String onExit) {
            super(node, uniqueName, onEnter,
                  String.format("%s|%s", Schema.ParserState.FIRST_ENTER_CONTEXT, Schema.ParserState.LAST_EXIT_CONTEXT),
                  Schema.ParserDirection.TO_DEVICE, onExit, null, null,
                  "com.tailf.packages.ned.nexus.NexusCliExtensions.injectOnEnterExitMode");
            this.devId = devId;
            this.key = key;
        }
    }

    private void reloadBehaviourSettings() throws Exception {
        HashMap<String, String> behaviourSettings = new HashMap<>();
        nedSettings.getMatching(behaviourSettings, "behaviours/");
        for (Map.Entry<String,String> entry: behaviourSettings.entrySet()) {
            String behaviour = entry.getKey();
            String value = entry.getValue();
            boolean enabled;
            if (value.equals("enable")) {
                enabled = true;
            } else if (value.equals("disable")) {
                enabled = false;
            } else {
                char compOp = value.charAt(0);
                value = value.substring(1);
                float compVersion = Float.valueOf(value).floatValue();
                if (compOp == '=') {
                    enabled = (nxosVersion == compVersion);
                } else if (compOp == '<') {
                    enabled = (nxosVersion < compVersion);
                } else {
                    enabled = (nxosVersion > compVersion);
                }
            }
            behaviour = behaviour.replace("behaviours/", "");
            log.debug((enabled ? "enabling " : "disabling ") + behaviour);
            behaviours.put(behaviour, enabled);
        }
        if (hasBehaviour("vtp-support")) {
            this.vtpStatusParser = readTextFSMTemplate("show_vtp_status.textfsm");
        }
        if (hasBehaviour("true-mtu-values")) {
            this.ifaceMTUParser = readTextFSMTemplate("show_iface_mtu.textfsm");
        }
        if (hasBehaviour("support-per-module-obfl")) {
            this.obflParser = readTextFSMTemplate("show_obfl_status.textfsm");
        }
        if (hasBehaviour("vrf-member-l3-redeploy")) {
            this.vrfMemberL3Redeploy = true;
        }
        this.cleartextProvisioning = hasBehaviour("cleartext-provisioning");
        this.provisionalTransidReusePctx = !cleartextProvisioning;
    }

    private String getVersionInfo(String version, String regex) {
        Pattern pat = Pattern.compile(regex);
        Matcher mat = pat.matcher(version);
        if (mat.find()) {
            return mat.group(1);
        }
        return "UNKNOWN";
    }


    @Override
    public boolean isAlive() {
        return (device != null) && device.isAlive();
    }


    private final static Pattern ethernetModifyPat =
        Pattern.compile("^((\\s*)interface Ethernet)((?:[0-9]+/[0-9]+/[0-9]+|[0-9]+/[0-9]+)(?:\\.[0-9]+)?)(\n(?:\\s+.*?\n)+)(\\2exit\n)",
                        Pattern.MULTILINE);
    private final static Pattern ethernetSubInterfaceDeletePat =
        Pattern.compile("^(\\s*no interface Ethernet)([0-9]+/[0-9]+/[0-9]+|[0-9]+/[0-9]+)(\\.[0-9]+)\\s*$",
                        Pattern.MULTILINE);
    private final static Pattern portChanModifyPat = // NOTE: the [^;] is to counter the quirk from 'delete-vlan-mappings-on-delete'
        Pattern.compile("^(\\s*)(interface port-channel)((?:[0-9]+)(?:\\.[0-9]+)?)(\\s+[^;].*?\n\\1)(exit\n)",
                        Pattern.MULTILINE | Pattern.DOTALL);
    private final static Pattern channelGroupBodyPat =
        Pattern.compile("^(\\s+channel-group [0-9]+)(.*?)$",
                        Pattern.MULTILINE);
    private final static Pattern noChannelGroupBodyPat =
        Pattern.compile("^\\s+no channel-group [0-9]+\\s*?$",
                        Pattern.MULTILINE);
    private final static Pattern switchportBodyPat =
        Pattern.compile("^\\s*switchport\\s*?$",
                        Pattern.MULTILINE);
    private final static Pattern noswitchportBodyPat =
        Pattern.compile("^\\s*no switchport\\s*?$",
                        Pattern.MULTILINE);

    private String interfaceBodyReorder(String body, String dividerLine, String[] beforePrefixes, String[] afterPrefixes) {
        String[] lines = body.split("\n");
        int div = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals(dividerLine)) {
                div = i;
                break;
            }
        }
        if (div == -1) {
            log.debug("divider not found, skip reorder of interface body");
            return body;
        }
        List<String> beforeLines = new ArrayList<>();
        List<String> afterLines = new ArrayList<>();
        for (int i = 0; i < div; i++) {
            String line = lines[i];
            boolean added = false;
            for (String pref: afterPrefixes) {
                if (line.trim().startsWith(pref)) {
                    added = true;
                    afterLines.add(line);
                }
            }
            if (!added) {
                beforeLines.add(line);
            }
        }
        for (int i = div + 1; i < lines.length; i++) {
            String line = lines[i];
            boolean added = false;
            for (String pref: beforePrefixes) {
                if (line.trim().startsWith(pref)) {
                    added = true;
                    beforeLines.add(line);
                }
            }
            if (!added) {
                afterLines.add(line);
            }
        }
        StringBuffer result = new StringBuffer();
        result.append("\n");
        for (String line: beforeLines) {
            if (line.trim().equals("")) {
                continue;
            }
            result.append(line);
            result.append("\n");
        }
        result.append(lines[div]);
        result.append("\n");
        for (String line: afterLines) {
            if (line.trim().equals("")) {
                continue;
            }
            result.append(line);
            result.append("\n");
        }
        return result.toString();
    }

    private String interfaceBodyFixEncapProf(String body) {
        String[] lines = body.split("\n");
        List<String> newBody = new ArrayList<>();
        String encapProfPrefix = null;
        for (String l : lines) {
            if (l.matches("^\\s*encapsulation profile \\S+\\s*$")) {
                log.debug(String.format("found encap: '%s' at %s", l, newBody.size()));
                encapProfPrefix = l;
            } else if (l.matches("^\\s+dot1q ([0-9]+)\\s*$")) {
                String vid = l.replaceAll("^\\s+dot1q ([0-9]+)\\s*$", "$1");
                log.debug("found dot1q add : " + l.replaceAll("^\\s+dot1q ([0-9]+)\\s*$", "$1"));
                newBody.add(encapProfPrefix + " dot1q add " + vid);
            } else if (l.matches("^\\s+no dot1q ([0-9]+)\\s*$")) {
                String vid = l.replaceAll("^\\s+no dot1q ([0-9]+)\\s*$", "$1");
                log.debug("found dot1q remove : " + l.replaceAll("^\\s+no dot1q ([0-9]+)\\s*$", "$1"));
                newBody.add(encapProfPrefix + " dot1q remove " + vid);
            } else if (l.matches("^\\s*no encapsulation profile \\S+\\s*$")) {
                log.debug(String.format("filter out fake '%s'", l));
            } else {
                log.debug("passthrough: " + l);
                newBody.add(l);
            }
        }
        if (encapProfPrefix != null) {
            StringBuffer sb = new StringBuffer();
            for (String l : newBody) {
                sb.append(l);
                sb.append("\n");
                body = sb.toString();
            }
        }
        return body;
    }

    private final Pattern vlanMappingPat = Pattern.compile("^( +switchport vlan mapping )([0-9]+)( dot1q-tunnel [0-9]+\n)",
                                                           Pattern.MULTILINE);
    private final Pattern vlanNoMappingPat = Pattern.compile("^( +no switchport vlan mapping )([0-9]+)( dot1q-tunnel [0-9]+\n)",
                                                             Pattern.MULTILINE);
    private String compressVlanMappings(String body) {
        body = compressRangeFor(vlanMappingPat, body);
        body = compressRangeFor(vlanNoMappingPat, body);
        return body;
    }

    protected NavuContainer getConfigContainer(int th) throws NavuException {
        NavuContainer context = new NavuContainer(new NavuContext(maapi, th));
        NavuContainer configContainer = null;
        if ((context.container(Ncs.hash) != null) &&
            (context.container(Ncs.hash).container(Ncs._devices_) !=  null) &&
            (context.container(Ncs.hash).container(Ncs._devices_).list(Ncs._device_) != null)) {
            NavuContainer config = context.container(Ncs.hash).
                container(Ncs._devices_).
                list(Ncs._device_).
                elem(new ConfKey(new ConfBuf(device_id)));
            if (config != null) {
                configContainer = config.container(Ncs._config_);
            }
        }
        return configContainer;
    }

    private String filterEthernetInterfaces(String config, int fromT, int toT) {
        Set<String> interfaces = new HashSet<>();
        Set<String> nonPCEthernetInterfacesList = new HashSet<String>();
        Set<String> switchports = new HashSet<>();
        Map<String, Integer> noswitchports = new HashMap<>();
        Map<String, String> leavingChanGroups = new HashMap<>();
        Map<String, String> joiningChanGroups = new HashMap<>();
        Map<String, Integer> chunkCount = new HashMap<>();
        Map<String, String> mtuBefore = new HashMap<>();

        // First pass:
        // count chunks (if bodies split in diff)
        // check for ethernet-interfaces:
        // - which will turn into l2-ports (i.e. 'switchport')
        // - which will turn into l3-ports (i.e. 'no switchport')
        // - which will leave channel-group
        Matcher matcher = ethernetModifyPat.matcher(config);
        boolean doMTURedeploy = hasBehaviour("switchport-mtu-redeploy");

        while (matcher.find()) {
            String ethIface = matcher.group(3);
            String body = matcher.group(4);

            int chunkNum;
            if (chunkCount.containsKey(ethIface)) {
                chunkNum = chunkCount.get(ethIface) + 1;
                chunkCount.put(ethIface, chunkNum);
            } else {
                chunkNum = 1;
                chunkCount.put(ethIface, 1);
            }

            Matcher switchportMatch = switchportBodyPat.matcher(body);
            if (switchportMatch.find()) {
                switchports.add(ethIface);
            }

            Matcher noswitchportMatch = noswitchportBodyPat.matcher(body);
            if (noswitchportMatch.find()) {
                log.debug(String.format("Interface %s, toggle to L3 in chunk %d", ethIface, chunkNum));
                noswitchports.put(ethIface, chunkNum);
            }

            Matcher noChanGroupCheck = noChannelGroupBodyPat.matcher(body);
            Matcher chanGroupCheck = channelGroupBodyPat.matcher(body);
            if (noChanGroupCheck.find()) {
                leavingChanGroups.put(ethIface, noChanGroupCheck.group(0));
            } else if (chanGroupCheck.find()) {
                log.debug("interface: Ethernet" + ethIface + ", is being added to channel-group, skip pruning, move 'channel-group to bottom'");
                String channelGroupJoin = null;
                if (hasBehaviour("force-join-channel-group")) {
                    channelGroupJoin = chanGroupCheck.group(1) + " force" + chanGroupCheck.group(2);
                } else {
                    channelGroupJoin = chanGroupCheck.group();
                }
                joiningChanGroups.put(ethIface, channelGroupJoin);
            }
        }

        // Filter out removal of breakout interfaces
        // (i.e. to enable breakout changes in a single transaction)
        if (!isDevice("netsim")) {
            config = config.replaceAll("no interface Ethernet[0-9]+/[0-9]+/[0-9]+\n", "");
        }

        // Quirk: prune out sub-interfaces which are implicitly wiped
        matcher = ethernetSubInterfaceDeletePat.matcher(config);
        StringBuffer cfgCopy = new StringBuffer();
        while (matcher.find()) {
            String ethIface = matcher.group(2);
            String line = matcher.group(0);
            if (switchports.contains(ethIface)) {
                line = "";
            }
            matcher.appendReplacement(cfgCopy, line);
        }
        matcher.appendTail(cfgCopy);
        config = cfgCopy.toString();

        try {
            NavuContainer configContainer = getConfigContainer(fromT);
            if (configContainer != null) {
                NavuContainer interfaceContainer = configContainer.container("nx", "interface");
                if ((interfaceContainer != null) && (interfaceContainer.list("nx", "Ethernet") != null)) {
                    NavuList ethList = interfaceContainer.list("nx", "Ethernet");
                    for (NavuContainer entry : ethList.elements()) {
                        String ethIface = entry.getKey().toString();
                        ethIface = ethIface.substring(1, ethIface.length() - 1);

                        NavuContainer chanGroup = entry.container("channel-group");
                        NavuLeaf chanIdLeaf = (chanGroup != null) ? chanGroup.leaf("id") : null;
                        String chanId = (chanIdLeaf != null) ? chanIdLeaf.valueAsString() : null;
                        if (chanId != null) {
                            log.debug("filter interface: " + ethIface);
                            interfaces.add(ethIface);
                        }
                        //VTS bug fix CSCvb77407, CSCvc45265 and CSCvd39926
                        else if (vtsPrivateFeatures) {
                            ethIface = entry.getKey().toString();
                            ethIface = ethIface.substring(1, ethIface.length() - 1);
                            if (entry.container("switchport").container("trunk").
                                container("allowed").container("vlan").
                                list("ids").size() > 0) {
                                nonPCEthernetInterfacesList.add(ethIface);
                                log.debug("filter allowed vlan none from ethernet interface: " + ethIface);
                            }
                        } // remove allowed vlan none if needed

                        if (doMTURedeploy && switchports.contains(ethIface)) {
                            ConfPath mtuPath = entry.leaf("mtu").getConfPath();
                            try {
                                if (maapi.exists(fromT, mtuPath) && maapi.exists(toT, mtuPath)) {
                                    String mtuFrom = ConfValue.getStringByValue(mtuPath, maapi.getElem(fromT, mtuPath));
                                    String mtuTo = ConfValue.getStringByValue(mtuPath, maapi.getElem(toT, mtuPath));
                                    if (mtuFrom.equals(mtuTo)) {
                                        // Not set in transaction, need to redeploy
                                        mtuBefore.put(ethIface, "mtu " + mtuTo);
                                    }
                                }
                            } catch (ConfException|IOException e) {
                                logError(getWorker(), String.format("Error getting value for redeploy of %s: ", mtuPath), e);
                            }
                        }
                    }
                } else {
                    log.debug("no Ethernet interfaces found in from-trans");
                }
            } else {
                log.debug("config container not found in from-trans");
            }
        } catch (NavuException e) {
            //
            log.debug("Navu error while finding ethernet interfaces in from transaction: " + e.getMessage());
        }

        // Second pass:
        // Prune channel-group interfaces from config not availble
        // Prune l3-config from interfaces turning into switchports
        // Prune l2-config from interfaces turning into l3-ports
        // Move 'no channel-group ...' to top in interfaces leaving channel-group
        // Move 'channel-group ...' to bottom in interfaces joining channel-group
        // Prune 'no switchport' from FEX-interfaces, can't be l3-interface
        StringBuilder result = new StringBuilder();
        matcher = ethernetModifyPat.matcher(config);
        int last = 0;
        Map<String, Integer> currChunkCnt = new HashMap<>();
        for (String iface : chunkCount.keySet()) {
            currChunkCnt.put(iface, 1);
        }
        while (matcher.find()) {
            String prefix = matcher.group(1);
            String ethIface = matcher.group(3);
            String body = matcher.group(4);
            String suffix = matcher.group(5);

            log.debug("interface before filter: " + prefix + ethIface + body + suffix);

            if (switchports.contains(ethIface)) {
                log.debug("reorder/prune l3-config in switchport: Ethernet" + ethIface);
                String[] beforePrefixes = { "ip address", "no ip address",
                                            "ipv6 address", "no ipv6 address",
                                            "ip router", "no ip router",
                                            "ipv6 router", "no ipv6 router",
                                            "ip redirects", "no ip redirects",
                                            "ipv6 redirects", "no ipv6 redirects",
                                            "ipv6 nd", "no ipv6 nd",
                                            "ip igmp", "no ip igmp",
                                            "ip ospf", "no ip ospf",
                                            "ospfv3", "no ospfv3",
                                            "ip pim", "no ip pim",
                                            "hsrp", "no hsrp",
                                            "isis", "no isis",
                                            "no mtu", "mtu 1500",
                                            "bfd", "no bfd",
                                            "ip arp ", "no ip arp ",
                                            "no vrf member"
                };
                if (mtuBefore.containsKey(ethIface)) {
                    // redeploy mtu when 'switchport-mtu-redeploy'
                    String mtuLine = mtuBefore.get(ethIface);
                    log.debug("redeploy " + mtuLine);
                    body += " " + mtuLine + "\n";
                }
                String[] afterPrefixes = { };
                // NOTE: mtu is not l3 only, but some nx-os/devices allows
                // setting default or jumbo, but will reset to default if
                // nothing set when toggling switchport (and some will disallow
                // mtu altogether in l2-mode)
                body = interfaceBodyReorder(body, "switchport",
                                            beforePrefixes,
                                            afterPrefixes);
            } else if (noswitchports.containsKey(ethIface)) {
                int toggleChunk = noswitchports.get(ethIface);
                int currChunk = currChunkCnt.get(ethIface);
                if (currChunk == toggleChunk) {
                    log.debug(String.format("reorder l2-config in l3-port: Ethernet%s (chunk %d)", ethIface, currChunk));
                    String[] beforePrefixes = { "no storm-control", "no spanning-tree", "no vpc orphan-port", "no l2protocol tunnel" };
                    String[] afterPrefixes = { };
                    body = interfaceBodyReorder(body, "no switchport",
                                                beforePrefixes,
                                                afterPrefixes);
                } else if (currChunk > toggleChunk) {
                    // Must prune, this is in chunk after we toggled to L3
                    log.debug(String.format("prune l2-config in l3-port: Ethernet%s (chunk %d)", ethIface, currChunk));
                    body = body.replaceAll("\\s+(?:no )?storm-control (?:broadcast|unicast|multicast) level .*?\n", "");
                    body = body.replaceAll("\\s+(?:no )?spanning-tree .*?\n", "");
                    body = body.replaceAll("\\s+no l2protocol tunnel .*?\n", "");
                }
            }

            // Transform encapsulation profile fake mode -> dot1q add/remove lines
            body = interfaceBodyFixEncapProf(body);

            body = compressVlanMappings(body);

            result.append(config.substring(last, matcher.start()));
            result.append(prefix);
            result.append(ethIface);

            if (interfaces.contains(ethIface)) {
                if (!leavingChanGroups.containsKey(ethIface)) {
                    log.debug("pruning channel-group interface: Ethernet" + ethIface);
                    body = body.replaceAll("\\s+(?:no )?switchport.*?\n", "");
                    body = body.replaceAll("\\s+(?:no )?fex associate [0-9]+.*?\n", "");
                    body = body.replaceAll("\\s+(?:no )?storm-control (?:broadcast|unicast|multicast) level .*?\n", "");
                    body = body.replaceAll("\\s+(?:no )?mtu.*?\n", "");
                    body = body.replaceAll("(\\s+channel-group [0-9]+)(.*?)", "$1 force$2"); // port is moved between groups
                } else if (body.contains("no channel-group")) {
                    // Only do this in the chunk containing the 'no channel-group...' if diff split
                    log.debug("interface: Ethernet" + ethIface + ", is being removed from channel-group, skip pruning, move 'no channel-group to top'");
                    body = body.replaceAll("\\s+no channel-group [0-9]+\\s*?\n", "\n");
                    body = "\n" + leavingChanGroups.get(ethIface) + "\n" + body;
                }
            } else if (joiningChanGroups.containsKey(ethIface)) {
                log.debug("interface: Ethernet" + ethIface + ", is being added to channel-group, skip pruning, move 'channel-group to bottom'");
                body = body.replaceAll("\\s+channel-group [0-9]+(?: mode \\S+)?\\s*?\n", "\n");
                if (currChunkCnt.get(ethIface) == chunkCount.get(ethIface)) {
                    // Last chunk
                    body = body + "\n" + joiningChanGroups.get(ethIface);
                }
            }
            //VTS bug fix CSCvb77407, CSCvc45265 and CSCvd39926
            else if (vtsPrivateFeatures && nonPCEthernetInterfacesList.contains(ethIface)) {
                log.debug("removing allowed vlan none in interface: " + ethIface);
                body = body.replaceAll("\\s+(?:no )?switchport trunk allowed vlan none.*?\n", "\n");
            }

            if (!body.startsWith("\n")) {
                body = "\n" + body;
            }
            if (!body.endsWith("\n")) {
                body = body + "\n";
            }
            log.debug("interface after filter: " + prefix + ethIface + body + suffix);
            result.append(body);
            result.append(suffix);
            last = matcher.end();
            // Inc curr chunk count
            currChunkCnt.put(ethIface, currChunkCnt.get(ethIface) + 1);
        }
        if (result.length() == 0) {
            return config;
        }
        result.append(config.substring(last));
        return result.toString();
    }

    private String filterPortChanInterfaces(String config, int fromT) {
        if (vtsPrivateFeatures) {
            //VTS bug fix CSCvb77407, CSCvc45265 and CSCvd39926
            config = filterAllPortChannelInterfaces(config, fromT);
        }

        // Only fix encap profiles here for now
        Matcher matcher = portChanModifyPat.matcher(config);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String prefix = matcher.group(2);
            String portChanIface = matcher.group(3);
            String body = matcher.group(4);
            String suffix = matcher.group(5);

            log.debug("interface before filter: " + prefix + portChanIface + body);

            // Transform encapsulation profile fake mode -> dot1q add/remove lines
            body = interfaceBodyFixEncapProf(body);

            body = compressVlanMappings(body);

            if (body.contains("no switchport")) {
                String[] beforePrefixes = { "no vpc " };
                String[] afterPrefixes = { };
                body = interfaceBodyReorder(body, "no switchport",
                                            beforePrefixes,
                                            afterPrefixes);
            }

            body = body.trim();
            if (body.contains("\n no shutdown") || body.startsWith("no shutdown")) {
                body = body.replaceAll("(?m)^ ?no shutdown$", "");
                body += "\n no shutdown";
            }

            log.debug("interface after filter: " + prefix + portChanIface + "\n" + body);
            matcher.appendReplacement(result, prefix + portChanIface + "\n " + Matcher.quoteReplacement(body) + "\n" + suffix);
        }
        matcher.appendTail(result);

        return result.toString();
    }

    // NOTE: this method is only used with vtsPrivateFeatures
    private final static Pattern portChannelModifyAllPat =
            Pattern.compile("^(\\s*interface port-channel)([0-9]+)([^!]*!)",
                    Pattern.MULTILINE | Pattern.DOTALL);
    private String filterAllPortChannelInterfaces(String config, int fromT) {
        Set<String> interfaces = new HashSet<String>();

        try {
            NavuContext context = new NavuContext(maapi, fromT);
            NavuList pcList = new NavuContainer(context)
                .container(Ncs.hash)
                .container(Ncs._devices_)
                .list(Ncs._device_)
                .elem(new ConfKey(new ConfBuf(device_id)))
                .container(Ncs._config_)
                .container("nx", "interface")
                .list("nx", "port-channel");

            for (NavuContainer entry : pcList.elements()) {
                log.debug("check pc interface: " + entry.getKey().toString());

                String pcIface = entry.getKey().toString();
                pcIface = pcIface.substring(1, pcIface.length() - 1);
                if (entry.container("switchport").container("trunk").
                    container("allowed").container("vlan").
                    list("ids").size() > 0) {
                    interfaces.add(pcIface);
                    log.debug("filter allowed vlan none from port channel interface: " + pcIface);
                }
            }
        } catch (NavuException e) {
            //
            log.debug("Navu error while finding ethernet interfaces in from transaction: " + e.getMessage());
        }

        StringBuilder result = new StringBuilder();
        final Matcher matcher = portChannelModifyAllPat.matcher(config);
        int last = 0;
        while (matcher.find() && interfaces.size() > 0) {
            String prefix = matcher.group(1);
            String pcIface = matcher.group(2);
            String body = matcher.group(3);
            result.append(config.substring(last, matcher.start()));
            result.append(prefix);
            result.append(pcIface);
            if (interfaces.contains(pcIface)) {
                log.debug("replacing in interface: " + pcIface);
                body = body.replaceAll("\\s+(?:no )?switchport trunk allowed vlan none.*?\n", "\n");
            }

            result.append(body);
            last = matcher.end();
        }
        if (result.length() == 0) {
            return config;
        }
        result.append(config.substring(last));
        return result.toString();
    }

    private final static Pattern itdModifyPat =
        Pattern.compile("^(\\s*)(itd )(\\S+)(\n\\s+)(.*?\\1)(exit\n)",
                        Pattern.MULTILINE | Pattern.DOTALL);
    private final static Pattern itdDGModifyPat =
        Pattern.compile("^(\\s*)(itd device-group )(\\S+)(\n\\s+)(.*?\\1)(!\n)",
                        Pattern.MULTILINE | Pattern.DOTALL);
    private final static Pattern itdDeletePat =
        Pattern.compile("^\\s*no itd (\\S+)\\s*?$",
                        Pattern.MULTILINE);
    @java.lang.SuppressWarnings("squid:S3457") // remove sonar warning about using \n in format strings
    private final String filterITD(NedWorker worker, String config, int fromT, int toT) throws NedException {
        StringBuffer result = new StringBuffer();
        try {
            NavuContainer toConfig = getConfigContainer(toT);
            NavuContainer fromConfig = getConfigContainer(fromT);

            if ((fromConfig == null) || (toConfig == null)) {
                // Nothing to do, from/to-transaction empty
                return config;
            }

            Map<String,Boolean> serviceActiveBefore = new HashMap<>();
            Map<String,Boolean> serviceActiveAfter = new HashMap<>();
            Map<String,String>  dgToService = new HashMap<>();
            NavuList itdsFrom = fromConfig.container("nx", "itd").list("service");
            for (NavuContainer itd : itdsFrom.elements()) {
                String name = itd.leaf("name").valueAsString();
                NavuLeaf shut = itd.leaf("shut");
                serviceActiveBefore.put(name, shut.exists() && "false".equals(shut.valueAsString()));
                NavuLeaf deviceGrp = itd.leaf("device-group");
                if (deviceGrp.exists()) {
                    dgToService.put(deviceGrp.valueAsString(), name);
                }
            }
            NavuList itdsTo = toConfig.container("nx", "itd").list("service");
            for (NavuContainer itd : itdsTo.elements()) {
                String name = itd.leaf("name").valueAsString();
                NavuLeaf shut = itd.leaf("shut");
                serviceActiveAfter.put(name, shut.exists() && "false".equals(shut.valueAsString()));
            }

            // Modify itd body when deleted, or toggle shut when edit
            Matcher matcher = itdModifyPat.matcher(config);
            while (matcher.find()) {
                String prefix = matcher.group(1) + matcher.group(2);
                String itdName = matcher.group(3);
                String indent = matcher.group(4);
                String body = matcher.group(5);
                String suffix = matcher.group(6);
                logDebug(worker, "Found itd body: " + matcher.group(3));
                boolean activeBefore = (serviceActiveBefore.get(itdName) != null) &&
                    serviceActiveBefore.get(itdName);
                boolean activeAfter = (serviceActiveAfter.get(itdName) != null) &&
                    serviceActiveAfter.get(itdName);
                if (activeBefore) {
                    body = body.replaceAll("(?m)^\\s*shut$", "");
                    body = "shut" + indent + body;
                }
                if (activeAfter && body.startsWith("shut") && !body.contains("\n no shut")) {
                    body = body + indent + "no shut";
                }
                body = body.trim();
                matcher.appendReplacement(result, prefix + itdName + indent + body + "\n" + suffix);
            }
            matcher.appendTail(result);
            config = result.toString();

            // Toggle connected service shut when edit
            result = new StringBuffer();
            matcher = itdDGModifyPat.matcher(config);
            while (matcher.find()) {
                String prefix = matcher.group(1) + matcher.group(2);
                String dgName = matcher.group(3);
                String indent = matcher.group(4);
                String body = matcher.group(5);
                String suffix = matcher.group(6);
                body = body.trim();
                String service = dgToService.get(dgName);
                boolean activeBefore = (serviceActiveBefore.get(service) != null) && serviceActiveBefore.get(service);
                boolean activeAfter = (serviceActiveAfter.get(service) != null) && serviceActiveAfter.get(service);
                if (activeBefore) {
                    prefix = String.format("itd %s\n shut\nexit\n%s", service, prefix);
                }
                if (activeBefore && activeAfter) {
                    suffix = String.format("%s\nitd %s\n no shut\nexit\n", suffix, service);
                }
                matcher.appendReplacement(result, prefix + dgName + indent + body + "\n" + suffix);
            }
            matcher.appendTail(result);

            config = result.toString();
            matcher = itdDeletePat.matcher(config);
            result = new StringBuffer();
            while (matcher.find()) {
                String name = matcher.group(1);
                logDebug(worker, "Found delete: " + name);
                matcher.appendReplacement(result, String.format("itd %s\n shut\nexit\n%s", name, matcher.group(0)));
            }
            matcher.appendTail(result);
        } catch (Exception e) {
            String msg = String.format("%s in filterITD(): %s", e.getClass().getName(), e.getMessage());
            logError(worker, msg, e);
            throw new NedException(msg);
        }

        return result.toString();
    }

    private final static Pattern evtMgrAppletPat =
        Pattern.compile("^(event manager applet \\S+(?: class \\S+)?\n)((?:[ \t]+.*?\n)+)",
                        Pattern.MULTILINE | Pattern.DOTALL);
    private final static Pattern delEvtSysLogTagLinePat =
        Pattern.compile("^\\s*no event syslog tag .*$",
                        Pattern.MULTILINE);
    private final String filterEvtMgrApplets(String config)
    {
        final Matcher matcher = evtMgrAppletPat.matcher(config);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String evtLines = matcher.group(2);
            StringBuffer appletBody = new StringBuffer();
            if (evtLines.contains("\n tag ")) {
                Matcher bodyMatcher = delEvtSysLogTagLinePat.matcher(evtLines);
                StringBuffer appletTail = new StringBuffer();
                while (bodyMatcher.find()) {
                    appletTail.append(bodyMatcher.group(0));
                    appletTail.append("\n");
                    bodyMatcher.appendReplacement(appletBody, "");
                }
                bodyMatcher.appendTail(appletBody);
                if (appletTail.length() > 0) {
                    appletBody.append("\n");
                    appletBody.append(appletTail);
                }
            } else {
                appletBody.append(evtLines);
            }
            matcher.appendReplacement(result, matcher.group(1) +
                                      Matcher.quoteReplacement(appletBody.toString()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    protected void appendRange(StringBuffer line, int start, int end)
    {
        if (line.length() > 0) {
            line.append(",");
        }
        line.append(Integer.toString(start));
        if (start != end) {
            line.append("-");
            line.append(Integer.toString(end));
        }
    }

    protected String compressRangeFor(Pattern pattern, String config) {
        Matcher matcher = pattern.matcher(config);
        Map<String,List<Integer>> compressRanges = new HashMap<>();
        StringBuffer newconf = new StringBuffer();
        while (matcher.find()) {
            String  prefix = matcher.group(1);
            Integer id = Integer.parseInt(matcher.group(2));
            String  suffix = matcher.group(3);
            String  key = prefix + ":" + suffix;
            if (!compressRanges.containsKey(key)) {
                matcher.appendReplacement(newconf, key + "_COMPRESS\n");
                compressRanges.put(key, new ArrayList<Integer>());
            } else {
                matcher.appendReplacement(newconf, "");
            }
            compressRanges.get(key).add(id);
        }
        matcher.appendTail(newconf);

        config = newconf.toString();

        for (Map.Entry<String, List<Integer>> entry : compressRanges.entrySet()) {
            int start = entry.getValue().get(0);
            int end = start - 1;
            StringBuffer range = new StringBuffer();
            for (Integer id: entry.getValue()) {
                if ((id - end) == 1) {
                    end = id;
                } else {
                    appendRange(range, start, end);
                    start = id;
                    end = id;
                }
            }
            if (start >= 0) {
                appendRange(range, start, end);
            }

            String[] parts = entry.getKey().split(":");
            config = config.replaceAll(entry.getKey() + "_COMPRESS\n", parts[0] + range.toString() + parts[1]);
        }

        return config;
    }

    protected static String groupAllMatching(Pattern pattern, String config, boolean last) {
        Matcher matcher = pattern.matcher(config);
        List<String> matches = new ArrayList<>();
        StringBuffer newconf = new StringBuffer();
        while (matcher.find()) {
            if (matches.isEmpty() && !last) {
                matcher.appendReplacement(newconf, "__GROUPALL__\n");
            } else {
                matcher.appendReplacement(newconf, "");
            }
            matches.add(matcher.group(0));
        }
        if (last) {
            newconf.append("__GROUPALL__\n");
        }
        matcher.appendTail(newconf);

        config = newconf.toString();

        newconf = new StringBuffer();
        for (String line : matches) {
            newconf.append(line);
        }
        config = config.replaceAll("__GROUPALL__\n", newconf.toString());

        return config;
    }
    static final Pattern noIpCommunityListSeq = Pattern.compile("^no ip community-list standard \\S+ seq.*\n", Pattern.MULTILINE);
    String resortNoIpCommunityLists(String config) {
        config = groupAllMatching(noIpCommunityListSeq, config, false);
        return config;
    }

    Pattern[] compressPatterns = {
        Pattern.compile("^(vlan )([0-9]+)(\\s*?\nexit\n)",
                        Pattern.MULTILINE),
        Pattern.compile("^(no vlan )([0-9]+)(\\s*?\n)",
                        Pattern.MULTILINE),
        Pattern.compile("^(spanning-tree vlan )([0-9]+)(\\s*\\S+ [0-9]+\n)",
                        Pattern.MULTILINE),
        Pattern.compile("^(no spanning-tree vlan )([0-9]+)(\\s*\\S+\n)",
                        Pattern.MULTILINE),
        Pattern.compile("^(spanning-tree mst )([0-9]+)(\\s*\\S+ [0-9]+\n)",
                        Pattern.MULTILINE),
        Pattern.compile("^(no spanning-tree mst )([0-9]+)(\\s*\\S+ [0-9]+\n)",
                        Pattern.MULTILINE),
        Pattern.compile("^(spanning-tree vlan no-list )([0-9]+)(\n)",
                        Pattern.MULTILINE),
        Pattern.compile("^(no spanning-tree vlan no-list )([0-9]+)(\n)",
                        Pattern.MULTILINE)
    };

    String compressListRanges(String config) {
        for (Pattern p : compressPatterns) {
            config = compressRangeFor(p, config);
        }
        return config;
    }

    protected String[] modifyData(NedWorker worker, String data, NedState state) throws NedException {
        String lines[];
        String line, nextline;
        int i;

        int fromT = -1;
        int toT = -1;
        NedProgress.Progress progress = reportProgressStart(this, NedProgress.TRANSFORM_OUT);
        try {
            // Handle custom extensions in diff
            handledOBUDelete = new HashSet<>();
            handledOBUEdit = new HashSet<>();
            String origDiff = data;
            data = parseCLIDiff(worker, data, true, state);

            // WARNING: Attach of fromT&toT must be done after parseCLIDiff or Exception in NAVU when used
            fromT = worker.getFromTransactionId();
            toT = worker.getToTransactionId();
            maapi.attach(fromT, 0);
            maapi.attach(toT, 0);

            if ((abortOnDiff || !"never".equals(traceOnDiff)) && (state == NedState.APPLY)) {
                EnumSet<MaapiConfigFlag> flags = EnumSet.of(MaapiConfigFlag.XML_FORMAT);
                String path = String.format("/ncs:devices/device{%s}/config", device_id);
                long s = tick(0);
                String xml = MaapiUtils.readConfig(maapi,
                                                   toT,
                                                   flags,
                                                   path);
                lastCDBContent = XMLUtils.xml2schema(xml, schema, "/config/devices/device/config");
                lastCDBContent.prune(Schema.DefaultHandlingMode.TRIM);
                logDebug(worker, String.format("Loaded CDB -> XML (%d bytes) %s", xml.length(), tickToString(s)));

                // TESTING TESTING
                // xml = MaapiUtils.readConfig(maapi,
                //                             fromT,
                //                             flags,
                //                             path);
                // Schema.TreeNode fromCDBContent = XMLUtils.xml2schema(xml, schema, "/config/devices/device/config");
                // Schema.TreeDiff diff = new Schema.TreeDiff(fromCDBContent, lastCDBContent);
                // logDebug(worker, "\nCalculated DIFF : \n" + diff.show().toString() + "\n");
                // Schema.ParserContext pctx = newParserContext(worker, Arrays.asList(origDiff.split("\n")),
                //                                              Schema.ParserDirection.TO_DEVICE,
                //                                              fromCDBContent);
                // pctx.parse();
                // fromCDBContent.prune();
                // diff = new Schema.TreeDiff(fromCDBContent, lastCDBContent);
                // if (!diff.isEmpty()) {
                //     logDebug(worker, "\nUpdated DIFF : \n" + diff.show().toString() + "\n");
                // }
                // TESTING TESTING
            }

            if (isDevice("virtual") == true) {
                data = data.replaceAll("\n\\s*(?:no )?switchport\\s*?\n", "\n");
            }

            if ((isDevice("netsim") == false) || this.debugPrepare) {
                data = filterEthernetInterfaces(data, fromT, toT);
                data = filterPortChanInterfaces(data, fromT);
                data = filterITD(worker, data, fromT, toT);

                data = filterEvtMgrApplets(data);

                // Work-around for NSO bug, presence + cli-display-separated -> deletes container before content
                data = data.replaceAll("(?m)^(\\s+no filter frame-type (?:ipv4|ipv6|arp|fcoe))((?:\n\\1 \\S+.*)+)", "$2\n$1");
            }

            // Compress list-ranges (workaround for buggy cli-range-list-syntax)
            data = compressListRanges(data);

            // Workaround for NSO bug, cli-remove-before-change doesn't trigger diff-deps
            data = resortNoIpCommunityLists(data);

            // Split into lines
            lines = data.split("\n");

            // Reorder lines
            for (i = 0; i < lines.length; i++) {
                line = lines[i];
                // put "no switchport" last
                if (line.matches("^\\s*no switchport\\s*$")) {
                    // found 'no switchport' only
                    for (; i < lines.length - 1; i++) {
                        nextline = lines[i+1];
                        if (!nextline.matches("^\\s*no switchport (\\S+).*$") &&
                            !nextline.matches("^\\s*switchport (\\S+).*$"))
                            break;
                        lines[i]   = nextline;
                        lines[i+1] = line;
                    }
                }
            }

            // Done
            reportProgressStop(progress);

        } catch (Exception e) {
            reportProgressStop(progress, "error");
            throw new NedException(e.getMessage(), e);

        } finally {
            maapiDetachToFrom(toT, fromT);
        }

        return lines;
    }

    private final static String META_TAG = "! meta-data";
    protected String modifyLine(NedWorker worker, String line)
        throws NedException {
        /*
         * NOTE: Don't use tailf:meta-data use custom extension instead
         */
        if (line.trim().startsWith(META_TAG)) {
            logError(worker, String.format("Ignoring tailf:meta-data: %s", line.trim()));
            return null;
        }

        // tailfned
        if ((line.indexOf("tailfned ") >= 0) && (isDevice("netsim") == false)) {
            logVerbose(worker, "SET " + line);
            return null;
        }

        if (line.matches(
             "\\s*(no )?route-target (import|export|both) [^\\s]+ \"\"\\s*$") ||
             line.indexOf("snmp-server community ") >= 0) {
            // Workaround cli-suppress-no not working
            line = line.replaceAll("\"", "");
        }
        // no-list - generic trick for no-lists
        else if (line.indexOf("no-list ") >= 0) {
            line = line.replace("no-list ", "");
            if (line.matches("^\\s*no .*$"))
                line = line.replace("no ", "");
            else
                line = "no " + line;
        } else if (line.matches("^\\s*no logging level\\s+otm\\s+[0-9]+$")) {
            line = line.replaceAll("^(\\s*no logging level)\\s+otm\\s+([0-9]+)$", "$1 track $2");
        } else if (line.matches("^\\s*logging logfile disable\\s*$")) {
            line = "no logging logfile";
        }

        return line;
    }

    protected boolean hasBehaviour(String name) {
        return behaviours.containsKey(name) && behaviours.get(name);
    }

    protected void disableBehaviour(String name) {
        behaviours.put(name, false);
    }

    protected void enableBehaviour(String name) {
        behaviours.put(name, true);
    }

    private void updateLastKnownConfig(NedWorker worker) throws NedException {
        boolean needConfigTree = abortOnDiff || !"never".equals(traceOnDiff);
        boolean doUpdate = needDeviceTransformedValues || needConfigTree || (configCacheEnabled && (lastKnownConfigTTL > 0L));
        lastKnownConfig = null;
        if (doUpdate) {
            // Get configuration and parse it to store values transformed by device
            try {
                if (!configCacheEnabled) {
                    // Temporarily set, will be reset when configCacheEnabled = false
                    lastKnownConfigTTL = 10*1000L;
                }
                logDebug(worker, String.format("updateLastKnownConfig() (TTL %d)", lastKnownConfigTTL));
                String config = getConfig(worker);
                Schema.ParserContext pctx = parseConfig(worker, Arrays.asList(config.split("\n")),
                                                        Schema.ParserDirection.FROM_DEVICE, NedState.SHOW);
                needDeviceTransformedValues = false;
            } catch (Exception e) {
                String msg = "Error getting config when updating last known: ";
                logError(worker, msg, e);
                throw new NedException(msg + e.getMessage());
            }
        }
    }

    /*
     NOTE: this method is only used with vtsPrivateFeatures
     Reads the configured sleep time
     The sleep time is a workaround for the suspected
     delay in devices updating show run at scale
    */
    private int getSleepTime(NedWorker worker) throws IOException {
        int delay = 0;
        String delay_str = null;
        NavuLeaf delay_leaf = null;
        int th = worker.getFromTransactionId();

        try {
            try {
                maapi.attach(th, 0);
                NavuContext context = new NavuContext(maapi, th);
                delay_leaf = new NavuContainer(context).container(Ncs.hash)
                        .container(Ncs._devices_)
                        .list(Ncs._device_)
                        .elem(new ConfKey(new ConfBuf(device_id)))
                        .leaf("delay-reading-applied-config");
                if (null != delay_leaf) {
                    delay_str = delay_leaf.valueAsString();
                    delay = Integer.parseInt(delay_str);
                }
            } finally {
                maapi.detach(th);
            }
        } catch (NavuException e) {
            log.debug("Navu error while finding read delay in from transaction: " + e.getMessage());
        } catch (IOException e) {
            log.debug("I/O error while finding read delay in from transaction" + e.getMessage());
        } catch (ConfException e) {
            log.debug("Conf error while finding read delay in from transaction" + e.getMessage());
        }

        log.debug("Sleeping for " + delay);
        return delay;
    }

    @Override
    public void
    applyConfig(NedWorker worker, int cmd, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {

        /*
         * Send command sequence to device
         * (NOTE: device must callback to modifyLine/modifyData).
         */
        device.applyConfig(worker, cmd, data);

        if (vtsPrivateFeatures) {
            // Arbitrary delay to workaround suspected
            //    delay in devices updating show run at scale
            try {
                Thread.sleep(getSleepTime(worker));
            } catch (InterruptedException e1) {
                log.error("sleep interrupted prematurely", e1);
                Thread.currentThread().interrupt();
            } catch (IOException e2) {
                log.error("failed to load sleep time", e2);
            }
        }

        String lastReply = device.lastReply;

        updateLastKnownConfig(worker);
        boolean traceOnDiffCommit = "commit".equals(traceOnDiff) || "always".equals(traceOnDiff);
        if (abortOnDiff || traceOnDiffCommit) {
            logDebug(worker, "Prepare for calculation of diff in apply");
            Schema.TreeDiff diff = new Schema.TreeDiff(lastCDBContent, showToTree(worker, lastKnownConfig, NedState.SHOW));
            if (!diff.isEmpty()) {
                long s = tick(0);
                StringBuilder diffTxt = diff.show(Schema.DiffFormat.TREE);
                String msg = String.format("\nDetected diff after apply %s:\n%s", tickToString(s), diffTxt);
                if (lastReply != null) {
                    msg = String.format("%s\nLast message from device:\n%s", msg, lastReply);
                }
                logError(worker, msg);
                if (abortOnDiff) {
                    throw new NedException(msg);
                }
            }
        }
    }

    @Override
    public void prepare(NedWorker worker, String data)
        throws Exception {

        if (useCLI && trace) {
            session.setTracer(worker);
        }

        try {
            if (!configCacheEnabled) {
                lastKnownConfigTTL = 0L;
            }
            this.applyConfig(worker, NedCmd.PREPARE_CLI, data);
        } finally {
            // If a transaction fails, reset
            needDeviceTransformedValues = false;
        }
        worker.prepareResponse();
    }


    @Override
    public void prepareDry(NedWorker worker, String data)
        throws Exception {
        String lines[];
        String line;
        StringBuilder newdata = new StringBuilder();
        int i;

        log.debug("PREPARE_DRY RAW: " + data);

        lines = modifyData(worker, data, NedState.DRY_RUN);

        for (i = 0; i < lines.length; i++) {
            line = modifyLine(worker, lines[i]);
            if ((line == null) || line.trim().equals(""))
                continue;
            newdata.append(line+"\n");
        }

        worker.prepareDryResponse(newdata.toString());
    }


    @Override
    public void abort(NedWorker worker, String data)
        throws Exception {

        if (useCLI && trace) {
            session.setTracer(worker);
        }

        applyConfig(worker, NedCmd.ABORT_CLI, data);
        worker.abortResponse();
    }


    @Override
    public void revert(NedWorker worker, String data)
        throws Exception {

        if (useCLI && trace) {
            session.setTracer(worker);
        }

        applyConfig(worker, NedCmd.REVERT_CLI, data);
        worker.revertResponse();
    }

    @Override
    public void commit(NedWorker worker, int timeout)
        throws Exception {
        if (useCLI && trace) {
            session.setTracer(worker);
        }

        worker.commitResponse();
    }

    @Override
    public void persist(NedWorker worker) throws Exception {
        if (useCLI && trace) {
            session.setTracer(worker);
        }

        if (useStrictPersistence) {
            device.saveConfig(worker);
        } else if (scheduleName != null) {
            String check_cmd = "show scheduler schedule name " + scheduleName;
            String r = device.command(worker, check_cmd, false);
            if (r.contains("Error: entry not found")) {
                throw new NedException("Fatal, cisco-nx-persistence/schedule/name '" +
                                       scheduleName + "' not found.");
            }
            if (!r.contains("Yet to be executed")) {
                log.info("Job '" + scheduleName +
                         "' present. Scheduling the job after " +
                         scheduleTime + " minutes");
                String schedule_cmd = "scheduler schedule name " + scheduleName + " ;time start +00:00:" + scheduleTime;
                device.command(worker, schedule_cmd, true);
            } else {
                Pattern pattern = Pattern.compile("[0-9][0-9]:[0-9][0-9]:[0-9][0-9]");
                Matcher matcher = pattern.matcher(r);
                if (matcher.find()) {
                    log.info("Job '" + scheduleName +
                             "' is already scheduled to be executed at " +
                             matcher.group(0));
                }
                else {
                    log.info("Job is already scheduled at unknown time");
                }
            }
        }

        worker.persistResponse();
    }

    protected boolean isDevice(String device) {
        return nxdevice.equals(device);
    }

    final static List<String> expandRanges(String rangeString) {
        String[] list = rangeString.trim().split("[, ]+");
        ArrayList<String> result = new ArrayList<>();
        for (String v: list) {
            if (v.contains("-")) {
                String[] r = v.split("-");
                for (int i = Integer.parseInt(r[0]); i <= Integer.parseInt(r[1]); i++) {
                    result.add(String.format("%d", i));
                }
            } else {
                result.add(v);
            }
        }
        return result;
    }

    boolean isRangeIncluded(String subRange, String totalRange) {
        Set<String> total = new HashSet<>();
        Set<String> sub = new HashSet<>();
        total.addAll(expandRanges(totalRange));
        sub.addAll(expandRanges(subRange));
        return total.containsAll(sub);
    }

    private String filterNonPrintable(String text)
    {
        return text.replaceAll("[\u0001-\u0008\u000b\u000c\u000e-\u001f]", "");
    }

    private String readFile(String file) throws IOException {
        BufferedReader reader = new BufferedReader(new java.io.FileReader (file));
        String         line = null;
        StringBuilder  stringBuilder = new StringBuilder();

        try {
            while((line = reader.readLine()) != null) {
                stringBuilder.append(line);
                stringBuilder.append("\r\n");
            }

            return stringBuilder.toString();
        } finally {
            reader.close();
        }
    }

    private String readTrace(NedWorker worker, String file) throws IOException {
        BufferedReader reader = new BufferedReader(new java.io.FileReader (file));
        String         line = null;
        StringBuilder  config = new StringBuilder();
        try {
            boolean foundConfig = false;
            int lineCnt = 0;
            boolean traceNXAPI = false;
            boolean stripPrompt = true;
            while ((line = reader.readLine()) != null) {
                if (!foundConfig) {
                    if (line.startsWith("-- NED VERSION: ") && !line.contains(" cisco-nx ")) {
                        traceInfo(worker, "Ignoring non cisco-nx NED trace: "+file);
                        return null;
                    } else if (line.trim().equals("!Command: show running-config")) {
                        foundConfig = true;
                        continue;
                    } else if (line.contains("<type>cli_show_ascii</type>")) {
                        traceNXAPI = true;
                    }
                } else {
                    if (line.startsWith("<< ") && line.contains("SET_TIMEOUT")) {
                        // trace file chaff
                        continue;
                    } else if (line.startsWith("  *** output") ||
                               (line.startsWith("<< ") && line.trim().endsWith("SHOW")))
                    {
                        // done
                        break;
                    } else if (line.trim().equals("__end_of_ned_cmd__") ||
                               (traceNXAPI && line.trim().equals("</body>"))) {
                        // done
                        stripPrompt = false;
                        break;
                    } else {
                        config.append(line);
                        config.append("\r\n");
                        lineCnt += 1;
                    }
                }
            }
            reader.close();
            if (!foundConfig) {
                traceInfo(worker, "No config found in NED trace: "+file);
                return null;
            } else {
                traceInfo(worker, String.format("Found config with %d lines in NED trace: %s", lineCnt, file));
                if (stripPrompt) {
                    // strip prompt at end of config
                    int i = config.lastIndexOf("#");
                    while (config.charAt(i) != '\r') {
                        i -= 1;
                    }
                    config.delete(i+2, config.length());
                }
            }
            return config.toString();
        } finally {
            reader.close();
        }
    }

    private final static Pattern descriptionPat =
        Pattern.compile("^((?:\\s*description )|(?:snmp-server (?:location|contact)[ ]+)|(?:cli alias name \\S+ )|(?:\\s+action \\S+[ ]+))(.*)$", Pattern.MULTILINE);
    private StringBuffer quoteDescriptionLeaves(StringBuffer config) {
        // Need to quote all description lines
        Matcher mat = descriptionPat.matcher(config);
        StringBuffer newconf = new StringBuffer();
        while (mat.find()) {
            String pref = mat.group(1);
            String desc = mat.group(2);
            if (pref.contains(" action ")) {
                // quirk for trailing space on /event/manager/applet/action
                desc = desc.trim();
            }
            mat.appendReplacement(newconf, pref + Matcher.quoteReplacement(stringQuote(desc)));
        }
        mat.appendTail(newconf);
        return newconf;
    }

    private void writeLastKnownConfig(NedWorker worker, String config) throws NedException {
        lastKnownConfigTime = System.currentTimeMillis();
        lastKnownConfig = config;
        writeCompressedLeaf(config, "last-known-config");
        try {
            long s = tick(0);
            ConfPath cp = new ConfPath(String.format("/ncs:devices/ncs:device{%s}/ncs:ned-settings/cisco-nx-oper/last-known-config-time", device_id));
            ConfValue cValue = new ConfUInt64(lastKnownConfigTime);
            cdbOper.setElem(cValue, cp);
            logDebug(worker, String.format("Wrote last known config (at %d), %d bytes %s",
                                           lastKnownConfigTime, lastKnownConfig.length(), tickToString(s)));
        } catch (ConfException|IOException e) {
            throw new NedException("Error in writeLastKnownConfig() : " + e.getMessage());
        }
    }

    private void readLastKnownConfig(NedWorker worker) throws NedException {
        try {
            long s = tick(0);
            lastKnownConfig = readCompressedLeaf("last-known-config");
            if (lastKnownConfig == null) {
                return;
            }
            ConfPath cp = new ConfPath(String.format("/ncs:devices/ncs:device{%s}/ncs:ned-settings/cisco-nx-oper/last-known-config-time", device_id));
            lastKnownConfigTime = (long)((ConfUInt64)cdbOper.getElem(cp)).bigIntegerValue().longValue();
            logDebug(worker, String.format("Read last known config (from %d), %d bytes %s",
                                           lastKnownConfigTime, lastKnownConfig.length(), tickToString(s)));
        } catch (ConfException|IOException e) {
            throw new NedException("Error in readLastKnownConfig() : " + e.getMessage());
        }
    }

    protected void writeCompressedLeaf(String data, String leafName) throws NedException {
        try {
            Deflater compressor = new Deflater();
            byte[] dataBytes = data.getBytes();
            byte[] compressed = new byte[data.length()];
            compressor.setInput(dataBytes);
            compressor.finish();
            int compressedLen = compressor.deflate(compressed);
            compressed = Arrays.copyOf(compressed, compressedLen);
            ConfValue cValue = new ConfBinary(compressed);
            ConfPath cp = new ConfPath(String.format("/ncs:devices/ncs:device{%s}/ncs:ned-settings/cisco-nx-oper/%s", device_id, leafName));
            cdbOper.setElem(cValue, cp);
            cp = new ConfPath(String.format("/ncs:devices/ncs:device{%s}/ncs:ned-settings/cisco-nx-oper/%s-sz", device_id, leafName));
            cValue = new ConfUInt32(dataBytes.length);
            cdbOper.setElem(cValue, cp);
        } catch (ConfException|IOException e) {
            throw new NedException(String.format("Error in writeCompressedLeaf(%s) : %s", leafName, e.getMessage()));
        }
    }

    protected String readCompressedLeaf(String leafName) throws NedException {
        try {
            ConfPath cp = new ConfPath(String.format("/ncs:devices/ncs:device{%s}/ncs:ned-settings/cisco-nx-oper/%s-sz", device_id, leafName));
            if (!cdbOper.exists(cp)) {
                return null;
            }
            int dataSz = (int)((ConfUInt32)cdbOper.getElem(cp)).longValue();
            cp = new ConfPath(String.format("/ncs:devices/ncs:device{%s}/ncs:ned-settings/cisco-nx-oper/%s", device_id, leafName));
            ConfBinary compressedBin = (ConfBinary)cdbOper.getElem(cp);
            byte[] compressed = compressedBin.bytesValue();
            Inflater decompressor = new Inflater();
            byte[] dataBytes = new byte[dataSz];
            decompressor.setInput(compressed);
            decompressor.inflate(dataBytes);
            return new String(dataBytes);
        } catch (DataFormatException|ConfException|IOException e) {
            throw new NedException(String.format("Error in readCompressedLeaf(%s) : %s", leafName, e.getMessage()));
        }
    }

    Schema.TreeNode showToTree(NedWorker worker, String config, NedState state) throws NedException {
        Schema.ParserContext pctx = parseConfig(worker, Arrays.asList(config.split("\n")),
                                                Schema.ParserDirection.FROM_DEVICE, state);
        pctx.dataTree.prune(Schema.DefaultHandlingMode.TRIM);
        return pctx.dataTree;
    }

    @java.lang.SuppressWarnings("squid:S3457") // remove sonar warning about using \n in format strings
    private String getConfig(NedWorker worker) throws Exception
    {
        String res;
        boolean doCacheConfig = false;
        NedProgress.Progress progress = reportProgressStart(this, NedProgress.READ_CONFIG);
        try {
            if (this.debugShowLoadFile != null) {
                res = readFile(this.debugShowLoadFile);
                log.debug("Read raw config from: " + this.debugShowLoadFile);
            } else if (offlineData != null) {
                res = offlineData;
            } else if ((lastKnownConfig != null) &&
                       ((System.currentTimeMillis()  - lastKnownConfigTime) < lastKnownConfigTTL)) {
                res = lastKnownConfig;
                if (!configCacheEnabled) {
                    // One shot cache, reset
                    lastKnownConfigTTL = 0L;
                }
                reportProgressStop(progress);
                return res;
            } else {
                res = device.getConfig(worker);
                doCacheConfig = (lastKnownConfigTTL > 0L) || !"never".equals(traceOnDiff);

                if (hasBehaviour("vtp-support")) {
                    String vtpStatus = device.command(worker, "show vtp status ; show vtp password", false);
                    vtpStatusParser.match(Arrays.asList(vtpStatus.split("\n")));
                    if (vtpStatusParser.matchedRows.size() > 0) {
                        Map<String,String> match = vtpStatusParser.matchedRows.get(0);
                        String versionRunning = match.get("versionRunning");
                        if (versionRunning.trim().length() > 0) {
                            StringBuilder vtpConfig = new StringBuilder();
                            vtpConfig.append(String.format("\nvtp version %s\n", versionRunning));
                            if (match.containsKey("password")) {
                                String pwd = match.get("password");
                                if (pwd.length() > 0) {
                                    vtpConfig.append(String.format("vtp password %s\n", pwd));
                                }
                            }
                            if (match.containsKey("pruningMode") && "enabled".equalsIgnoreCase(match.get("pruningMode"))) {
                                vtpConfig.append(String.format("vtp pruning\n"));
                            }
                            vtpConfig.append(String.format("vtp mode %s\n", match.get("operatingMode").toLowerCase()));
                            String domain = match.get("domainName");
                            if (!domain.equals("-") && !domain.startsWith("<VTP")) {
                                vtpConfig.append(String.format("vtp domain %s\n", domain));
                            }
                            res += vtpConfig.toString();
                        }
                    }
                }
                if (hasBehaviour("true-mtu-values")) {
                    String ifaceMTUList = device.command(worker, "show interface | inc \"Ethernet[0-9]|MTU\"", false);
                    ifaceMTUParser.match(Arrays.asList(ifaceMTUList.split("\n")));
                    StringBuilder ifaceExtra = new StringBuilder();
                    ifaceExtra.append("\n");
                    for (Map<String,String> row : ifaceMTUParser.matchedRows) {
                        ifaceExtra.append(String.format("interface %s\n mtu %s\n!\n", row.get("EthIface"), row.get("MTU")));
                    }
                    if ("disable".equals(nedSettings.getString("system-interface-defaults/handling"))) {
                        res += ifaceExtra.toString();
                    } else {
                        res = ifaceExtra.toString() + res;
                    }
                }
                if (hasBehaviour("support-per-module-obfl")) {
                    String obflModList = device.command(worker, "show logging onboard status", false);
                    obflParser.match(Arrays.asList(obflModList.split("\n")));
                    StringBuilder obflStatus = new StringBuilder();
                    obflStatus.append("\n");
                    for (Map<String,String> row : obflParser.matchedRows) {
                        String module = row.get("Module");
                        if ("Disabled".equals(row.get("Status"))) {
                            logDebug(worker, String.format("OBFL log disabled for module %s", module));
                            continue;
                        }
                        for (Map.Entry<String,String> entry : row.entrySet()) {
                            String key = entry.getKey();
                            if ("Module".equals(key)) {
                                continue;
                            }
                            String val = entry.getValue();
                            if (val == null) {
                                continue;
                            }
                            boolean enabled = "Enabled".equals(val);
                            key = key.replaceAll("_", "-").toLowerCase();
                            obflStatus.append(String.format("%shw-module logging onboard module %s %s\n",
                                                            enabled ? "" : "no ", module, key));
                        }
                    }
                    res = obflStatus.toString() + res;
                }
            }
            reportProgressStop(progress);
        } catch (Exception e) {
            reportProgressStop(progress, "error");
            throw e;
        }

        res = res.replace("\r", "");

        log.debug("SHOW_BEFORE=\n"+res);

        // NETSIM fixes
        if (!isDevice("netsim")) {
            // Do this after banner, since the banner can contain ctrl-characters as delimiters
            res = filterNonPrintable(res);

            // tailf:cli-preformatted means stringDequote() on prepare()
            // but we still need to quote input...
            StringBuffer newconf = new StringBuffer(res);
            newconf = quoteDescriptionLeaves(newconf);

            res = newconf.toString();
        }

        log.debug("SHOW_AFTER("+device_id+")=\n"+res);

        if (doCacheConfig) {
            writeLastKnownConfig(worker, res);
        }

        // Respond with updated show buffer
        return res;
    }

    private void maapiDetachToFrom(int toT, int fromT) {
        try { if (toT != -1) { maapi.detach(toT); } } catch (Exception e) { /* ignore */ }
        try { if (fromT != -1) { maapi.detach(fromT); } } catch (Exception e) { /* ignore */ }
    }

    final static Map<String, Set<Schema.Node>> categoryMap = new HashMap<>();
    final static Map<Schema.Node, List<Schema.Node>> deviceTransformedDeps = new HashMap<>();
    @Override
    protected void handleStatement(Schema.Node node, Schema.Ystmt stmt) {
        if (stmt.keyword.startsWith(NedMetaData.moduleName)) {
            String extension = stmt.keyword.substring(stmt.keyword.indexOf(':')+1);
            if ("data-category".equals(extension)) {
                final String category = stmt.arg;
                if (categoryMap.get(category) == null) {
                    categoryMap.put(category, new HashSet<Schema.Node>());
                }
                node.traverse(new Schema.SchemaTraverser() {
                        public boolean visitNode(Schema.Node node) {
                            categoryMap.get(category).add(node);
                            return true;
                        }
                    });
            } else if ("tri-state-boolean".equals(extension) &&
                       (node.token.length() == 0) &&
                       (node.yangOrder > 0)) {
                Schema.Node sibling = node.parent.getChild(node.yangOrder-1);
                Schema.CallbackMetaData triStateBoolean =
                    Schema.CallbackMetaData.createFromTemplate(node, stmt);
                sibling.annotations.addCallback(triStateBoolean);
                node.annotations.removeCallback("nx:tri-state-boolean");
            } else if ("redeploy-dependency".equals(extension)) {
                String depPath = stmt.arg;
                if (depPath.indexOf('[') > -1) {
                    depPath = depPath.replaceAll("\\[[^\\]]+?\\]", "");
                }
                Schema.Node depNode = node.getSibling(depPath);
                Schema.Node commonContext = node.getCommonContext(depNode).self;
                NexusCliExtensions.RedeployHandler.addTo(commonContext, depNode);
            } else if ("device-transformed-dependency".equals(extension)) {
                Schema.Container parent = node.getMySubtree();
                if (!deviceTransformedDeps.containsKey(parent)) {
                    deviceTransformedDeps.put(parent, new ArrayList<Schema.Node>());
                }
                deviceTransformedDeps.get(parent).add(node);
            }
        } else if (stmt.keyword.equals("tailf-common:cli-range-list-syntax")) {
            Schema.CallbackMetaData fixRangeSyntax =
                new Schema.CallbackMetaData(node, "cli:fix-range-syntax", null,
                                            "pre-match",
                                            Schema.ParserDirection.FROM_DEVICE,
                                            null, null, null,
                                            "com.tailf.packages.ned.nexus.NexusCliExtensions.fixRangeSyntax");
            node.annotations.addCallback(fixRangeSyntax);
        } else if (stmt.keyword.equals("default") &&
                   node.xpath.startsWith("/snmp-server/enable/traps") && // !!! Hard-coded for now
                   (node instanceof Schema.Leaf) &&
                   !node.inSequence() &&
                   (((Schema.Leaf)node).defaultMode != Schema.DefaultHandlingMode.REPORT_ALL) &&
                   !node.annotations.hasSubStmt("nx:trim-default-in-diff")) {
            Schema.CallbackMetaData trimDefaultInDiff =
                new Schema.CallbackMetaData(node, "nx:trim-default-in-diff", null,
                                            "post-match",
                                            Schema.ParserDirection.TO_DEVICE,
                                            null, null, null,
                                            "com.tailf.packages.ned.nexus.NexusCliExtensions.trimDefaultInDiff");
            node.annotations.addCallback(trimDefaultInDiff);
        }
    }

    @Override
    protected void setupParserContext(Schema.ParserContext parserContext) {
        parserContext.addCallback(NedMetaData.moduleName + "-setup", new Schema.ParserCallback() {
                public void callTarget(Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
                    NedWorker worker = (NedWorker)parserContext.externalContext;
                    for (Map.Entry<String, Boolean> entry : behaviours.entrySet()) {
                        String xpath = String.format("/tailfned/%s", entry.getKey());
                        Schema.Node node = schema.getNode(xpath);
                        String key = entry.getKey();
                        String value = "";
                        if (behaviourBooleans.contains(key)) {
                            value = entry.getValue() ? "true" : "false";
                        } else if (!entry.getValue()) {
                            // Only inject empty leaf when true (i.e. enabled)
                            continue;
                        }
                        if (node == null) {
                            parserContext.addVirtualLeaf(xpath, value);
                        } else {
                            if (!(node instanceof Schema.Leaf)) {
                                throw new Error(String.format("Can't inject non-leaf node: %s", node.xpath));
                            }
                            schema.addData(parserContext.dataTree, (Schema.Leaf)node, value);
                        }
                        logDebug(worker, String.format("ned-settings -> %s %s %s", (node != null) ? "config" : "virtual", xpath, value));
                    }
                    if (!hasBehaviour("port-channel-load-balance-ethernet") && detectedLoadBalanceEthernet) {
                        schema.addData(parserContext.dataTree, (Schema.Leaf)schema.getNode("/tailfned/port-channel-load-balance-ethernet"), "");
                    }
                    if (!"disable".equals(nedSettings.getString("system-interface-defaults/handling"))) {
                        parserContext.addVirtualLeaf("/tailfned/inject-switchport-defaults", "true");
                        parserContext.addVirtualLeaf("/tailfned/system-default-switchport", defaultSwitchport);
                        parserContext.addVirtualLeaf("/tailfned/system-default-switchport-shutdown", defaultSwitchportShutdown);
                        parserContext.addVirtualLeaf("/tailfned/system-default-l3-port-shutdown", nedSettings.getString("system-interface-defaults/default-l3-port-shutdown"));
                        parserContext.addVirtualLeaf("/tailfned/system-default-fc-shutdown", nedSettings.getString("system-interface-defaults/default-fc-shutdown"));

                        if (hasBehaviour("dayzero-included")) {
                            schema.addData(parserContext.dataTree,
                                           (Schema.Leaf)schema.getNode("/system/default/switchport"),
                                           defaultSwitchport);
                            schema.addData(parserContext.dataTree,
                                           (Schema.Leaf)schema.getNode("/system/default/switchport-config/switchport/shutdown"),
                                           defaultSwitchportShutdown);
                        }

                        logDebug(worker, String.format("Using %s system-interface-defaults handling :", nedSettings.getString("system-interface-defaults/handling")));
                        logDebug(worker, String.format("\t/tailfned/system-default-switchport = %s", defaultSwitchport));
                        logDebug(worker, String.format("\t/tailfned/system-default-switchport-shutdown = %s", defaultSwitchportShutdown));
                    }
                    if (!"0".equals(nedSettings.getString("api/snmp-server-enable-all-traps"))) {
                        schema.addData(parserContext.dataTree, (Schema.Leaf)schema.getNode("/tailfned/snmp-server-enable-all-traps"), "");
                    }
                    List<String> linesToInject = parserContext.emitCLI(parserContext.dataTree);
                    if (linesToInject.size() > 0) {
                        if (parserContext.parserDirection == Schema.ParserDirection.FROM_DEVICE) {
                            parserContext.injectImmediate(linesToInject);
                        }
                        logDebug(worker, "Will inject the following ned-settings mapped to config:");
                        for (String l : linesToInject) {
                            logDebug(worker, String.format("\t'%s'", l));
                        }
                    } else {
                        logDebug(worker, "Nothing to inject for mapped ned-settings");
                    }
                }
            });
    }

    @Override
    protected void preParseNotify(NedWorker worker, Schema.ParserContext parserContext, NedState state) {
        switch (state) {
        case SHOW:
            if ((offlineData == null) && cleartextProvisioning) {
                transformedValues = new HashSet<>(); // Reset to be able to garbage-collect postParseNotify
            }
            if (hasBehaviour("support-case-insensitive-type")) {
                caseInsensitiveValues = new HashSet<>();
            }
            this.vrfMemberL3Redeploy = hasBehaviour("vrf-member-l3-redeploy");
            break;
        case DRY_RUN:
        case TRANS_ID:
            if (cleartextProvisioning) {
                disableBehaviour("cleartext-provisioning");
            }
            break;
        default:
            break;
        }
    }

    @Override
    protected void postParseNotify(NedWorker worker, Schema.ParserContext parserContext, NedState state) {
        switch (state) {
        case SHOW:
            if ((transformedValues != null) && cleartextProvisioning) {
                // Garbage-collect the secrets
                NavuContext context = new NavuContext(cdbOper);
                try {
                    ConfPath cp = new ConfPath(String.format("/ncs:devices/ncs:device{%s}/ncs:ned-settings/secrets:secrets/secret", device_id));
                    NavuList secretsList = (NavuList)new NavuContainer(context).getNavuNode(cp);
                    logDebug(worker, "Cleaning cached cleartext -> device transformed values");
                    for (NavuContainer entry : secretsList.elements()) {
                        String id = entry.leaf("id").valueAsString();
                        if (!transformedValues.contains(id) && cdbOper.exists(entry.getConfPath())) {
                            logDebug(worker, "GC of value in: " + id);
                            cdbOper.delete(entry.getConfPath());
                        }
                    }
                } catch (ConfException|IOException e) {
                    logError(worker, "Error accessing cdb-oper while clearing secrets", e);
                } finally {
                    transformedValues = null;
                    context.removeCdbSessions();
                }
            }
            transformedValues = null;
            if (caseInsensitiveValues != null) {
                // Garbage-collect the case-insensitive values
                NavuContext context = new NavuContext(cdbOper);
                try {
                    ConfPath cp = new ConfPath(String.format("/ncs:devices/ncs:device{%s}/ncs:ned-settings/cisco-nx-oper/node-map", device_id));
                    NavuList caseList = (NavuList)new NavuContainer(context).getNavuNode(cp);
                    logDebug(worker, "Cleaning cached case-insensitive values");
                    for (NavuContainer entry : caseList.elements()) {
                        String id = entry.leaf("id").valueAsString();
                        if (!caseInsensitiveValues.contains(id) && cdbOper.exists(entry.getConfPath())) {
                            logDebug(worker, "GC of value in: " + id);
                            cdbOper.delete(entry.getConfPath());
                        }
                    }
                } catch (ConfException|IOException e) {
                    logError(worker, "Error accessing cdb-oper while clearing case-insensitive mappings", e);
                } finally {
                    caseInsensitiveValues = null;
                    context.removeCdbSessions();
                }
            }
            break;
        case DRY_RUN:
        case TRANS_ID:
            if (cleartextProvisioning) {
                enableBehaviour("cleartext-provisioning");
            }
            break;
        default:
            break;
        }
    }

    @Override
    protected String getDeviceConfiguration(NedWorker worker, NedState state)
        throws Exception
    {
        if (state == NedState.TRANS_ID) {
            boolean traceOnDiffCheckSync = "check-sync".equals(traceOnDiff) || "always".equals(traceOnDiff);
            // Temporarily set, will be reset when configCacheEnabled = false
            if (traceOnDiffCheckSync && (lastKnownConfigTTL == 0L)) {
                lastKnownConfigTTL = 5*1000L;
            }
        }

        String config = getConfig(worker);

        if ((state == NedState.TRANS_ID) && (transIdMethod == TransIdMethod.FULL_CONFIG)) {
            // Remove all comment lines, affects transid (e.g. timestamp)
            config = config.replaceAll("(?m)^!.*$", "");
        }
        return config;
    }

    @Override
    protected String getCustomTransId(NedWorker worker)
        throws Exception
    {
        String res = device.command(worker, deviceTransIdCmd, false);
        if ((res.indexOf("Invalid command") >= 0) ||
            (res.indexOf("Error") >= 0)) {
            throw new NedException("Error when running cisco-nx/transaction/trans-id-cmd '" +
                                   deviceTransIdCmd + "': " + res);
        }
        res = res.trim();
        return calculateMd5Sum(res);
    }

    @Override
    protected void setTransIdResponse(NedWorker worker, String id) throws Exception {
        boolean traceOnDiffCheckSync = "check-sync".equals(traceOnDiff) || "always".equals(traceOnDiff);
        if (traceOnDiffCheckSync) {
            try {
                ConfPath cp = new ConfPath(String.format("/ncs:devices/ncs:device{%s}/ncs:ned-settings/cisco-nx-oper/last-set-trans-id", device_id));
                if (cdbOper.exists(cp)) {
                    long s = tick(0);
                    String lastTransId = cdbOper.getElem(cp).toString();
                    String lastTransIdConfig = readCompressedLeaf("last-transid-config");
                    if ((lastTransIdConfig != null) && !id.equals(lastTransId)) {
                        logDebug(worker, "Prepare for calculation of diff in trans-id");
                        Schema.TreeNode previousTree = showToTree(worker, lastTransIdConfig, NedState.TRANS_ID);
                        Schema.TreeNode currentTree = showToTree(worker, lastKnownConfig, NedState.TRANS_ID);
                        Schema.TreeDiff diff = new Schema.TreeDiff(previousTree, currentTree);
                        StringBuilder diffTxt = diff.show(Schema.DiffFormat.TREE);
                        String msg = String.format("\nDiff from previous trans-id %s:\n%s", tickToString(s), diffTxt);
                        logInfo(worker, msg);
                    }
                }
                writeCompressedLeaf(lastKnownConfig ,"last-transid-config");
                cdbOper.setElem(new ConfBuf(id), cp);
            } catch (ConfException|IOException e) {
                throw new NedException("Error in setTransIdResponse() : " + e.getMessage());
            }
        }
        super.setTransIdResponse(worker, id);
    }

    @Override
    public void show(NedWorker worker, String toptag)
        throws Exception {
        if (useCLI && trace && (session != null)) {
            session.setTracer(worker);
        }

        if (toptag.equals("interface")) {
            log.info("show("+device_id+")");
            String res = getConfig(worker);
            if (turboParserEnable && parseAndLoadXMLConfigStream(maapi, worker, schema, res)) {
                res = "";
            } else if (robustParserMode) {
                res = filterConfig(res, schema, maapi, worker, null, false).toString();
            }
            worker.showCliResponse(res);
        } else {
            // only respond to first toptag
            worker.showCliResponse("");
        }
    }

    // @Override
    public void showOffline(NedWorker worker, String toptag, String data)
        throws Exception {
        try {
            logDebug(worker, "Doing showOffline");
            this.offlineData = data;
            show(worker, toptag);
        } finally {
            this.offlineData = null;
        }
    }

    @java.lang.SuppressWarnings("squid:S3457") // remove sonar warning about using \n in format strings
        protected void checkConfigDir(NedWorker worker, String dir, boolean concat, StringBuilder result)
        throws Exception
    {
        File f = new File(dir);
        FilenameFilter filter = new FilenameFilter() {
                @Override
                public boolean accept(File f, String name) {
                    return name.endsWith(".trace");
                }
            };
        File[] files = f.listFiles(filter);
        if (files == null || files.length == 0) {
            throw new NedException("No such directory or no trace file in: "+dir);
        }
        StringBuilder all = new StringBuilder();
        try {
            result.append("\n--------------------------------------------------------------------------------\n");
            for (File file : files) {
                String name = file.getAbsolutePath();
                this.offlineData = readTrace(worker, name);
                if (this.offlineData != null) {
                    all.append(getConfig(worker));
                    all.append("\n! PARSER_EXIT_TO_TOP\n");
                }
                if (!concat) {
                    result.append(String.format("File %s :\n", name));
                    checkConfig(worker, all, result);
                    result.append("--------------------------------------------------------------------------------\n");
                    all = new StringBuilder();
                }
            }
            if (concat) {
                result.append(String.format("  Number of files parsed           : %6d\n", files.length));
                checkConfig(worker, all, result);
            }
        } finally {
            this.offlineData = null;
        }
    }

    @java.lang.SuppressWarnings("squid:S3457") // remove sonar warning about using \n in format strings
        protected void checkConfig(NedWorker worker, StringBuilder config, StringBuilder result)
        throws NedException
    {
        String[] lines = config.toString().split("\n");
        Schema.ParserContext pctx = parseConfig(worker, Arrays.asList(lines),
                                                Schema.ParserDirection.FROM_DEVICE, NedState.TRANS_ID);

        List<String> failureList = getParseFailureList(pctx, true);

        int noDupFailCnt = 0;
        int totFailCnt = pctx.getFailCnt();

        for (String unknown : failureList) {
            if (unknown.trim().startsWith("(line")) {
                noDupFailCnt += 1;
            }
        }

        result.append(String.format("  Number of lines parsed           : %6d\n", pctx.getLineCnt()));
        if (noDupFailCnt != totFailCnt) {
            result.append(String.format("  Number of lines skipped (total)  : %6d\n", totFailCnt));
            result.append(String.format("  Number of lines skipped (unique) : %6d\n", noDupFailCnt));
        } else {
            result.append(String.format("  Number of lines skipped          : %6d\n", totFailCnt));
        }
        result.append(String.format("  Time to parse config (ms)        : %6d\n", pctx.parseTime));
        for (String l : getParseFailureList(pctx, true)) {
            result.append(l);
            result.append("\n");
        }
    }

    @Override
    public void command(NedWorker worker, String cmdName, ConfXMLParam[] p)
        throws Exception {
        String cmd  = cmdName;
        String reply = "";
        boolean configCmd = cmdName.equals("exec");
        boolean needRecaptureEncrypted = false;

        if (useCLI && trace) {
            session.setTracer(worker);
        }

        if (p.length < 1) {
            worker.error(NedCmd.CMD, "missing argument(s) for subcmd="+cmdName);
        }

        /* Explicitly set configured read timeout, NX-312 */
        worker.setTimeout(readTimeout);

        String hiddenAnswers = null;
        if (cmdName.equals("any-hidden")) {
            ConfXMLParam p1 = p.length > 1 ? p[1] : null;
            if ("answers".equals(p[0].getTag())) {
                hiddenAnswers = p[0].getValue().toString();
                cmd = p1.getValue().toString();
            } else {
                cmd = p[0].getValue().toString();
                hiddenAnswers = (p1 != null) ? p1.getValue().toString() : "";
            }
        } else {
            /* Add arguments */
            for (int i = 0; i < p.length; ++i) {
                ConfObject val = p[i].getValue();
                if (val != null)
                    cmd = cmd + " " + val.toString();
            }

            if (cmd.indexOf("any ") == 0)
                cmd = cmd.substring(4);
            else if (cmd.indexOf("exec ") == 0)
                cmd = cmd.substring(5);
            else if ((cmd.startsWith("ping ") || cmd.startsWith("traceroute ")) &&
                     (cmd.indexOf(':') >= 0)) {
                // Quirk for auto detect ipv6 with ping/traceroute
                cmd = cmd.replaceFirst(" ", "6 ");
            }
        }

        if (cmd.startsWith("internal ")) {
            if (cmd.startsWith("internal check-config-")) {
                cmd = cmd.substring("internal check-config-".length()).trim();
                boolean concat = cmd.startsWith("all");
                String dir = cmd.substring(cmd.indexOf(' ')+1).trim();
                StringBuilder result = new StringBuilder();
                checkConfigDir(worker, dir, concat, result);
                reply = result.toString();
            } else if (cmd.startsWith("internal compare-config")) {
                if (lastCDBContent == null) {
                    readCDBRunning(worker);
                }
                // force reload of config from device
                needDeviceTransformedValues = true;
                updateLastKnownConfig(worker);
                Schema.TreeNode deviceTree = showToTree(worker, lastKnownConfig, NedState.SHOW);
                Schema.TreeDiff diff = new Schema.TreeDiff(lastCDBContent, deviceTree);
                reply = diff.show(Schema.DiffFormat.TREE).toString();
            } else {
                throw new NedException("Unkonwn internal cmd: " + cmd.substring(9));
            }
        } else {
            // Extract answer(s) to prompting questions
            String[] answers = null;
            if (hiddenAnswers != null) {
                answers = hiddenAnswers.trim().split(",");
            } else {
                Pattern pattern = Pattern.compile("(.*)\\[([^\\]]*)\\]\\s*");
                Matcher matcher = pattern.matcher(cmd);
                if (matcher.find()) {
                    cmd = matcher.group(1).trim();
                    answers = matcher.group(2).trim().split(",");
                }
            }

            if (hiddenAnswers == null) {
                log.debug("executing " + (configCmd ? "config " : "") + "command on device: " + cmd);
                if (answers != null) {
                    String msg = "[";
                    for (String a : answers) {
                        if (msg.length() > 1) {
                            msg += ", ";
                        }
                        msg += a.trim();
                    }
                    msg += "]";
                    log.debug("answers  " + msg);
                }
            }

            needRecaptureEncrypted = cleartextProvisioning &&
                (cmd.contains("encryption re-encrypt obfuscated") || cmd.contains("encryption decrypt type6"));

            boolean isTracing = trace;
            if (isTracing && (hiddenAnswers != null) && !logVerbose) {
                traceInfo(worker, "Sending (hidden) line...");
                session.setTracer(null);
                trace = false;
            }
            if (!splitLiveStatsExecAny) {
                reply = device.command(worker, cmd, (!configCmd ?
                                                     NexusDevice.CommandType.EXEC_CMD :
                                                     NexusDevice.CommandType.CONFIG_CMD),
                                       answers);
            } else {
                String[] cmds = cmd.split(" ; ");
                if ((answers != null) && (answers.length != cmds.length)) {
                    throw new NedException("When using 'split-exec-any', length of answers must match number of commands given");
                }
                String[] oneans = null;
                for (int i = 0; i < cmds.length; i++) {
                    reply += "\n> " + cmds[i];
                    if (answers != null) {
                        oneans = new String[] { answers[i] };
                    }
                    NexusDevice.CommandType cmdType =
                        ((!configCmd || i > 0) ?
                         NexusDevice.CommandType.EXEC_CMD :
                         NexusDevice.CommandType.FIRST_CONFIG_CMD);
                    reply += device.command(worker, cmds[i], cmdType, oneans);
                }
                if (configCmd && useCLI) {
                    device.command(worker, "end", NexusDevice.CommandType.EXEC_CMD, null);
                }
            }
            if (isTracing && (hiddenAnswers != null) && !logVerbose) {
                session.setTracer(worker);
                trace = true;
            }

            if (cmd.startsWith("traceroute") &&
                reply.trim().endsWith("1")) {
                // Ugly quirk to filter out strange trailing '1' from device (NX-483)
                reply = reply.substring(0, reply.lastIndexOf('1'));
            }
        }

        if (needRecaptureEncrypted) {
            needDeviceTransformedValues = true;
            forceUpdateTransformedValues = true;
            updateLastKnownConfig(worker);
            forceUpdateTransformedValues = false;
        }

        reply = filterNonPrintable(reply);

        // Report reply
        worker.commandResponse(new ConfXMLParam[]
            {
                new ConfXMLParamValue(configCmd ? "nx" : "nx-stats", "result",
                                      new ConfBuf(reply))
            });
    }

    @Override
    protected void cleanup() throws Exception {
        if (device != null) {
            device.close();
        }
    }

}
