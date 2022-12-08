package com.tailf.packages.ned.asa;

import com.tailf.packages.ned.nedcom.NedComCliBase;
import com.tailf.packages.ned.nedcom.NedSecrets;
import com.tailf.packages.ned.nedcom.NedSettings;
import com.tailf.packages.ned.nedcom.livestats.NedLiveStats;
import com.tailf.packages.ned.nedcom.livestats.NedLiveStatsException;
import com.tailf.packages.ned.nedcom.livestats.NedLiveStatsShowHandler;
import com.tailf.packages.ned.nedcom.NedCommonLib.PlatformInfo;
import static com.tailf.packages.ned.nedcom.NedString.*; // NOSONAR

import java.io.IOException;
import java.io.BufferedReader;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.MessageDigest;

import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Date;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.concurrent.Semaphore;

import java.lang.reflect.Method;

import com.tailf.conf.Conf;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfValue;
import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfList;
import com.tailf.conf.ConfException;
import com.tailf.conf.ConfXMLParam;
import com.tailf.conf.ConfXMLParamValue;

import com.tailf.maapi.MaapiUserSessionFlag;
import com.tailf.maapi.MaapiCrypto;
import com.tailf.maapi.MaapiCursor;
import com.tailf.maapi.MaapiException;

import com.tailf.ned.NedCmd;
import com.tailf.ned.NedException;
import com.tailf.ned.NedExpectResult;
import com.tailf.ned.NedMux;
import com.tailf.ned.NedWorker;

import com.tailf.ned.SSHSessionException;
import com.tailf.ned.SSHSession;
import com.tailf.ned.CliSession;

import com.tailf.navu.NavuAction;
import com.tailf.navu.NavuContainer;
import com.tailf.navu.NavuContext;
import com.tailf.navu.NavuNode;


/**
 * Implements the cisco-asa CLI NED
 * @author lbang
 *
 */
@SuppressWarnings("deprecation")
public class ASANedCli extends NedComCliBase {

    /*
     * Constants
     */
    private static final String META_DATA = "! meta-data :: ";

    private static final int TRACE_INFO = 6;
    private static final int TRACE_DEBUG = 7;
    private static final int TRACE_DEBUG2 = 8;
    private static final int TRACE_DEBUG3 = 9;

    /*
     * Prompts
     */

    // start of input, > 0 non-# and ' ', one #, >= 0 ' ', eol
    private static final String EXEC_PROMPT = "\\A[^\\# ]+#[ ]?$";
    private static final String CONFIG_PROMPT = "\\A.*\\(.*\\)#";

    // NEDLIVESTATS prompts
    private static final Pattern[] NEDLIVESTATS_PROMPT = new Pattern[] {
        Pattern.compile(CONFIG_PROMPT),
        Pattern.compile(EXEC_PROMPT)
    };

    private static final Pattern[] PLW = new Pattern[] {
        // 0 - questions
        Pattern.compile("Continue\\?\\[confirm\\]"),
        Pattern.compile("\\?[ ]?\\[yes/no\\]"),
        // 2 - Configuration Replication
        Pattern.compile("WARNING: Configuration syncing is in progress"),
        // 3 - prompts
        Pattern.compile("\\A.*\\(cfg\\)#"),
        Pattern.compile("\\A.*\\(config\\)#"),
        Pattern.compile(CONFIG_PROMPT),
        Pattern.compile("\\A\\S*#")
    };

    private static final Pattern[] config_prompt_patterns = new Pattern[] {
        Pattern.compile("\\A\\S*\\(config\\)#"),
        Pattern.compile(CONFIG_PROMPT)
    };

    private static final Pattern[] ec = new Pattern[] {
        Pattern.compile("Do you want to kill that session and continue"),
        Pattern.compile("\\A\\S*\\(config\\)#"),
        Pattern.compile(CONFIG_PROMPT),
        Pattern.compile("Aborted.*\n"),
        Pattern.compile("Error.*\n"),
        Pattern.compile("syntax error.*\n"),
        Pattern.compile("error:.*\n")
    };

    private static final Pattern[] ec2 = new Pattern[] {
        Pattern.compile("\\A.*\\(cfg\\)#"),
        Pattern.compile("\\A.*\\(config\\)#"),
        Pattern.compile(CONFIG_PROMPT),
        Pattern.compile("Aborted.*\n"),
        Pattern.compile("Error.*\n"),
        Pattern.compile("syntax error.*\n"),
        Pattern.compile("error:.*\n")
    };


    /*
     * applyConfig() errors
     */

    // The following strings is an error -> abort transaction
    // Note Alphabetically sorted.
    private static final String[] errfail = {
        "aborted",
        "addresses overlap with existing localpool range",
        "a .* already exists for network",
        "bad mask",
        "being used",
        "cannot apply",
        "cannot be deleted",
        "cannot configure",
        "cannot have local-as same as bgp as number",
        "cannot negate",
        "cannot redistribute",
        "command is depreceated",
        "command rejected",
        "configuration not accepted",
        "configure .* first",
        "create .* first",
        "disable .* first",
        "does not exist",
        "does not support .* configurations",
        "duplicate name",
        "enable .* globally before configuring",
        "entry already running and cannot be modified",
        "error",
        "exceeded",
        "failed",
        "first configure the",
        "has already been assigned to",
        "hash values can not exceed 255",
        "illegal",
        ".* is being un/configured in sub-mode, cannot remove it",
        "in use, cannot",
        "incomplete",
        "inconsistent address.*mask",
        "incorrect .* configured",
        "interface .* already configured as default ",
        "is configured as .* already",
        "interface.* combination tied to .* already",
        "invalid",
        "not valid",
        "not a valid ",
        "is not logically valid",
        "is not permitted",
        "is not running",
        "is not supported",
        "is used by",
        "local-as allowed only for ebgp peers",
        "may not be configured",
        "must be configured first",
        "must be enabled first",
        "must be disabled first",
        "must be greater than",
        "must be removed first",
        "must configure ip address for",
        "must enable .* routing first",
        "must specify a .* port as the next hop interface",
        "network: ip address/mask .* doesn't pair",
        "no authentication servers found",
        "no existing configuration binding the default",
        "no such",
        "not added",
        "not allowed",
        "not available",
        "not configured",
        "not defined",
        "not enough memory",
        "not found",
        "not supported",
        "peer* combination tied to .* already",
        "please configure .* before configuring",
        "please remove .*",
        "please 'shutdown' this interface before trying to delete it",
        "previously established ldp sessions may not have",
        "protocol not in this image",
        "range already exists",
        "routing not enabled",
        "setting rekey authentication rejected",
        "should be greater than",
        "should be in range",
        "specify .* command.* first",
        "sum total of .* exceeds 100 percent",
        "table is full",
        "unable to add",
        "unable to set_.* for ",
        "unable to populate"
    };


    /*
     * Local data
     */

    // Context
    private static Semaphore maapiSemaphore = new Semaphore(1);
    private static HashMap<String, Semaphore> adminSem = new HashMap<>();
    private boolean haveContext = false;
    private AdminSSH adminSSH = null;
    private SCPSession scpSession = null;
    private String scpAddress = null;
    private ArrayList<String> modifiedContextList = null;
    private String configUrl = null;

    // devices device platform
    private String asaversion = "unknown";
    private String asamodel = "unknown";
    private String asaname = "asa";
    private String asaserial = "unknown";

    // Utility classes
    private MetaDataModify metaData;
    private NedSecrets secrets;
    private ConfigArchive configArchive;
    private NedCommand nedCommand;
    protected MaapiCrypto mCrypto;
    private ModeStack modeStack = new ModeStack();

    // States
    private String confRoot;
    private long lastTimeout;
    private String lastTransformedConfig = null;
    private String lastGetConfig = null;
    private boolean haveConfigSession = false;
    private boolean showRaw = false;
    private String syncFile = null;
    private String offlineData = null;
    private String nsoUser = null;
    private String simFail = "";
    protected int lastTransactionId = 0;

    // NED-SETTINGS
    private String transActionIdMethod;
    private String writeMemoryMode;
    protected int writeSlowNewlineDelay;
    private boolean writeSerMaapiAclRead;
    private int chunkSize;
    private String contextName;

    private String adminDeviceName;
    private String adminDeviceMethod;
    private int adminDeviceNumberOfRetries;
    private int adminDeviceTimeBetweenRetry;
    private int adminDeviceMaxSessions;

    private String scpDir;
    private String scpFile;
    private String scpDeviceName;
    private String scpDevice2Address;
    private boolean scpDeviceLookup;
    private String scpCliFallback;

    private int scpWriteThreshold;
    private String scpReadMethod;

    private boolean useStartupConfig;
    private String configSessionMode;
    private boolean compressAclDelete;
    private int recreateAclThreshold;
    private String devModel;
    private int devTraceLevel;
    private String devProgVerbosity;
    private long devDelayShow;
    private long devDelayApply;
    private ArrayList<String[]> autoPrompts = new ArrayList<>();
    private ArrayList<String[]> contextList = new ArrayList<>();


    /*
     **************************************************************************
     * Constructors
     **************************************************************************
     */

    /**
     * NED cisco-asa constructor
     */
    public ASANedCli() {
        super();
    }


    /**
     * NED huawei-vrp constructor
     * @param device_id
     * @param mux
     * @param trace
     * @param worker
     */
    public ASANedCli(String deviceId,
                     NedMux mux,
                     boolean trace,
                     NedWorker worker) throws Exception {
        super(deviceId, mux, trace, worker);
        confRoot = "/ncs:devices/ncs:device{"+device_id+"}/config/asa:";
    }


    /*
     **************************************************************************
     * nedSettingsDidChange
     **************************************************************************
     */

    /**
     * Cache ned-settings in NED. Called when ned-settings changed
     * @param
     * @throws Exception
     */
    @Override
    public void nedSettingsDidChange(NedWorker worker, Set<String> changedKeys, boolean isConnected) throws Exception {
        final long start = tick(0);
        logInfo(worker, "BEGIN nedSettingsDidChange");

        setUserSession(worker);
        int th = maapi.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);
        try {
            List<Map<String,String>> entries;

            // write
            writeMemoryMode = nedSettings.getString("write/memory-setting");
            configSessionMode = nedSettings.getString("write/config-session-mode");
            chunkSize = nedSettings.getInt("write/number-of-lines-to-send-in-chunk");
            compressAclDelete = nedSettings.getBoolean("write/compress-acl-delete");
            recreateAclThreshold = nedSettings.getInt("write/recreate-acl-threshold");
            writeSlowNewlineDelay = nedSettings.getInt("write/slow-newline-delay");
            writeSerMaapiAclRead = nedSettings.getBoolean("write/serialize-maapi-acl-read");

            // read
            transActionIdMethod = nedSettings.getString("read/transaction-id-method");
            useStartupConfig = nedSettings.getBoolean("read/use-startup-config");
            if ("disabled".equals(writeMemoryMode) && useStartupConfig) {
                throw new NedException("read/use-startup-config can't be used in combination with"
                                       +" write/memory-setting = disabled");
            }

            // scp-transfer
            scpDir = nedSettings.getString("scp-transfer/directory");
            scpFile = nedSettings.getString("scp-transfer/file");
            scpFile = scpFile.replace("%h", device_id);
            scpDeviceName = nedSettings.getString("scp-transfer/device-name");
            scpDevice2Address = nedSettings.getString("scp-transfer/device2-address");
            scpDeviceLookup = nedSettings.getBoolean("scp-transfer/device-lookup");
            scpCliFallback = nedSettings.getString("scp-transfer/cli-fallback");

            scpWriteThreshold = nedSettings.getInt("write/scp-transfer/threshold");
            scpReadMethod = nedSettings.getString("read/scp-transfer/method");

            // context name
            contextName = nedSettings.getString("context/name");
            if (contextName == null) {
                traceInfo(worker, "context/name = null");
            }

            // admin-device
            adminDeviceName = nedSettings.getString("admin-device/name");
            if (adminDeviceName == null) {
                traceInfo(worker, "admin-device/name = null");
            } else {
                if (contextName != null && !useStartupConfig) {
                    throw new NedException("read/use-startup-config must be true with context-name and admin-device");
                }
                adminDeviceMethod = nedSettings.getString("admin-device/method");
                if (adminDeviceMethod.startsWith("ssh")) {
                    adminDeviceNumberOfRetries = nedSettings.getInt("admin-device/number-of-retries");
                    adminDeviceTimeBetweenRetry = nedSettings.getInt("admin-device/time-between-retry");
                }
                adminDeviceMaxSessions = nedSettings.getInt("admin-device/max-sessions");
            }

            // context list *
            if (contextName == null && adminDeviceName == null) {
                entries = nedSettings.getListEntries("context/list");
                for (Map<String,String> entry : entries) {
                    String[] newEntry = new String[2];
                    newEntry[0] = entry.get("__key__"); // "name"
                    newEntry[1] = "true".equals(entry.get("hide-configuration")) ? "hide-cfg" : "";
                    traceInfo(worker, "context/list "+stringQuote(newEntry[0])+" "+newEntry[1]);
                    contextList.add(newEntry);
                }
            }

            // live-status
            entries = nedSettings.getListEntries("live-status/auto-prompts");
            for (Map<String,String> entry : entries) {
                String[] newEntry = new String[3];
                newEntry[0] = entry.get("__key__"); // "id"
                newEntry[1] = entry.get("question");
                newEntry[2] = entry.get("answer");
                traceVerbose(worker, "live-status/auto-prompts "+newEntry[0]
                             + " q \"" +newEntry[1]+"\""
                             + " a \"" +newEntry[2]+"\"");
                autoPrompts.add(newEntry);
            }

            // developer
            devModel = nedSettings.getString(NedSettings.DEVELOPER_MODEL);
            devTraceLevel = nedSettings.getInt("developer/trace-level");
            if (logVerbose && devTraceLevel < TRACE_DEBUG) {
                traceInfo(worker, "ned-settings log-verbose true => developer trace-level = 7 (DEBUG)");
                devTraceLevel = TRACE_DEBUG;
            }
            devProgVerbosity = nedSettings.getString(NedSettings.DEVELOPER_PROG_VERBOSITY);
            devDelayShow = (long)nedSettings.getInt("developer/delay/show");
            devDelayApply = (long)nedSettings.getInt("developer/delay/apply");

            // write config-archive *
            configArchive = new ConfigArchive(this);
            configArchive.init(worker);

        } catch (Exception e) {
            throw new NedException("Failed to read ned-settings :: "+e.getMessage(), e);
        } finally {
            maapi.finishTrans(th);
        }
        logInfo(worker, "DONE nedSettingsDidChange "+tickToString(start));
    }


    /*
     **************************************************************************
     * setupDevice
     **************************************************************************
     */

    /**
     * Setup device
     * @param
     * @return PlatformInfo
     * @throws Exception
     */
    protected PlatformInfo setupDevice(NedWorker worker) throws Exception {
        tracer = trace ? worker : null;

        //
        // Logged in, set terminal settings and check device type
        //
        final long start = tick(0);
        logInfo(worker, "BEGIN PROBE");
        try {
            resetTimeout(worker, this.connectTimeout, 0);

            // Set terminal settings (context = admin for multi-context)
            print_line_exec(worker, "terminal pager 0");

            // Issue show version to check device/os type
            String version = print_line_exec(worker, "show version");
            if (!version.contains("Cisco Adaptive Security Appliance")) {
                throw new NedException("Unknown device :: " + version);
            }

            //
            // Found NETSIM
            //
            if (version.contains("NETSIM")) {
                traceVerbose(worker, "Found NETSIM");
                asamodel = "NETSIM";
                asaversion = "NED " + nedVersion;
                asaserial = device_id;

                // Show CONFD&NED version used by NETSIM in ned trace
                print_line_exec(worker, "show confd-state version");
                print_line_exec(worker, "show confd-state loaded-data-models data-model tailf-ned-cisco-asa");

                // Set NETSIM terminal settings
                print_line_exec(worker, "terminal length 0");
                print_line_exec(worker, "terminal width 0");
            }

            //
            // Found real device
            //
            else {
                traceVerbose(worker, "Found real device");

                // Reset terminal settings
                print_line_exec(worker, "terminal no monitor");

                // Verify terminal width
                String terminal = print_line_exec(worker, "show terminal");
                Pattern p = Pattern.compile("Width = (\\d+)");
                Matcher m = p.matcher(terminal);
                if (m.find()) {
                    int width = Integer.parseInt(m.group(1));
                    if (width > 0 && width < 511) {
                        throw new NedException ("Device terminal width ("+width+") too low,"
                                                +" please pre-configure to 0 or 511");
                    }
                }

                // cisco-asa write config-session-mode
                haveConfigSession = false;
                if (configSessionMode.contains("enabled")) {
                    try {
                        // First check if config sessions are supported by showing nso session
                        String res = print_line_exec(worker, "show configuration session | i session nso");
                        if (isExecError(res)) {
                            throw new NedException(res);
                        } else if (res.contains("session nso")) {
                            // Delete previous incomplete nso config session
                            print_line_exec(worker, "clear session nso configuration");
                        }
                        print_line_exec(worker, "clear configuration session");
                        traceInfo(worker, "Using config-session-mode with access-list and object(-group) configuration\n");
                        haveConfigSession = true;
                    } catch (Exception e) {
                        traceInfo(worker, "Disabled config-session-mode due to lack of device support\n");
                    }
                }

                // Optionally setup context
                String mode = print_line_exec(worker, "show mode");
                if (mode.contains("multiple")) {
                    setupContext(worker);
                }

                // Get version
                int e;
                int b = version.indexOf("Software Version");
                if (b > 0) {
                    e = version.indexOf('\n', b);
                    if (e > 0) {
                        asaversion = version.substring(b+17,e).trim();
                    }
                }
                if (haveConfigSession) {
                    asaversion += " <ses>";
                }

                // Get model
                b = version.indexOf("\nHardware: ");
                if (b > 0) {
                    asamodel = version.substring(b+11);
                    if ((e = asamodel.indexOf(',')) > 0) {
                        asamodel = asamodel.substring(0,e);
                    }
                    if ((e = asamodel.indexOf('\n')) > 0) {
                        asamodel = asamodel.substring(0,e);
                    }
                    asamodel = asamodel.trim();
                }

                // Get serial
                p = Pattern.compile("Serial Number:\\s+(\\S+)");
                m = p.matcher(version);
                if (m.find()) {
                    asaserial = m.group(1);
                } else {
                    // user context, look up serial from inventory
                    String inventory = print_line_exec(worker, "show inv 0");
                    p = Pattern.compile("SN:\\s+(\\S+)");
                    m = p.matcher(inventory);
                    if (m.find()) {
                        asaserial = m.group(1);
                    }
                }
            }

        } catch (Exception e) {
            throw new NedException("Failed to setup NED :: "+e.getMessage(), e);
        }

        logInfo(worker, "DONE PROBE "+tickToString(start));
        return new PlatformInfo(asaname, asaversion, asamodel, asaserial);
    }


    /**
     * Setup admin SSH connection
     * @param
     * @throws Exception
     */
    private void setupAdminSSH(NedWorker worker, String deviceName, int openth) throws Exception {

        // cisco-asa admin-device method = ssh-reuse
        if (adminSSH != null) {
            traceInfo(worker, "admin-device: SSH reusing adminSSH");
            return;
        }

        int th = openth;
        try {
            setUserSession(worker);
            if (th == -1) {
                th = maapi.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);
            }

            // Get device address, port and authgroup
            String aaddress = getDeviceSetting(deviceName, th, "address");
            String aport = getDeviceSetting(deviceName, th, "port");
            if (aport == null) {
                aport = "22";
            }
            String aauthgroup = getDeviceSetting(deviceName, th, "authgroup");
            if (aaddress == null || aauthgroup == null) {
                throw new NedException("admin-device: SSH config error :: incomplete "+deviceName+" device");
            }

            // Get authgroup name, password and secondary-password
            String aname = getAuthGroupSetting(worker, th, aauthgroup, "remote-name");
            String apass = getAuthGroupSetting(worker, th, aauthgroup, "remote-password");
            if (aname == null || apass == null) {
                throw new NedException("admin-device: SSH config error :: incomplete "+aauthgroup+" authgroup");
            }
            apass = mCrypto.decrypt(apass);
            String asecpass = getAuthGroupSetting(worker, th, aauthgroup, "remote-secondary-password");
            if (asecpass != null && !asecpass.isEmpty()) {
                asecpass = mCrypto.decrypt(asecpass);
            }

            // Get SSH AdminSSH instance
            adminSSH = AdminSSH.getInstance(worker, device_id, deviceName, logVerbose,
                                            aaddress,aport,aname,apass,asecpass,
                                            connectTimeout, adminDeviceNumberOfRetries,
                                            adminDeviceTimeBetweenRetry, adminDeviceMaxSessions);
        } finally {
            if (openth == -1 && th != -1) {
                maapi.finishTrans(th);
            }
        }
    }


    /**
     *
     * @param
     * @return
     * @throws Exception
     */
    private String getDeviceSetting(String device, int th, String path) throws Exception {
        String p = "/ncs:devices/device{"+device+"}/"+path;
        try {
            if (maapi.exists(th, p)) {
                return ConfValue.getStringByValue(p, maapi.getElem(th, p));
            }
        } catch (MaapiException ignore) {
            // Ignore Exception
        }
        return null;
    }


    /**
     *
     * @param
     * @return
     * @throws Exception
     */
    private String getAuthGroupSetting(NedWorker worker, int thr, String authgroup, String path) throws Exception {
        String umap = "default-map";
        if (nsoUser != null) {
            try {
                String p = "/ncs:devices/authgroups/group{"+authgroup+"}/umap{"+nsoUser+"}";
                if (maapi.exists(thr, p)) {
                    umap = "umap{"+nsoUser+"}";
                    traceVerbose(worker, "authgroup umap = "+nsoUser);
                }
            } catch (MaapiException ignore) {
                // Ignore Exception
            }
        }
        String p = "/ncs:devices/authgroups/group{"+authgroup+"}/"+umap+"/"+path;
        try {
            if (maapi.exists(thr, p)) {
                return ConfValue.getStringByValue(p, maapi.getElem(thr, p));
            }
        } catch (MaapiException ignore) {
            // Ignore Exception
        }
        return null;
    }


    /**
     *
     * @param
     * @throws Exception
     */
    private void setupContext(NedWorker worker) throws Exception {

        traceInfo(worker, "Using security context mode : multiple");
        this.haveContext = true;

        // Get context info
        String showContext = print_line_exec(worker, "show context");
        if (showContext.contains("ERROR")) {
            throw new NedException("Failed to execute 'show context' on multiple mode device");
        }

        //
        // Admin capable login
        //
        if (showContext.contains("\n*")) {
            if (contextName != null) {
                throw new NedException("context/name must not be set on admin capable device");
            }
            traceInfo(worker, "Found admin context on multiple context mode device");

            // Set pager 0 for system context
            changeto_context(worker, "system", true);
            print_line_exec(worker, "terminal pager 0");

            return;
        }

        //
        // Single context login
        //
        // Context Name   Class Interfaces Mode URL
        // <NAME> <CLASS> <INT_LIST> <MODE> <URL>
        String[] tokens = showContext.trim().split("[ \r\n]+");
        if (tokens.length < 11) {
            throw new NedException("Failed to parse 'show context' output:\n" + showContext);
        }

        // Autoderive context name if not set by user via NED settings
        if (contextName == null) {
            contextName = tokens[6].trim();
            traceInfo(worker, "context/name not set, setting to: /"+contextName);
        }

        // Verify that context name shown is what configured in ned-setting
        if (!tokens[6].trim().equals(contextName)) {
            throw new NedException("Wrong context found '"+tokens[6].trim()+"' expected '"
                                   +contextName+"', check context/name ned-setting config");
        }

        // Set config url for future file retrival
        configUrl = tokens[10].trim();
        traceInfo(worker, "Found single context /"+contextName+" with config url '"+configUrl+"'");
    }


    /*
     **************************************************************************
     * setupInstance
     **************************************************************************
     */

    /**
     * Setup NED instance
     * @param
     * @throws Exception
     */
    protected void setupInstance(NedWorker worker, PlatformInfo platformInfo)
        throws Exception {
        final long start = tick(0);
        logInfo(worker, "BEGIN SETUP");

        nsoUser = worker.getRemoteUser();
        traceInfo(worker, "NSO user = "+nsoUser);

        this.asamodel = platformInfo.model;
        this.asaversion = platformInfo.version;
        this.asaserial = platformInfo.serial;

        // Trace device profile
        setUserSession(worker);
        int th = maapi.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);
        getDeviceProfile(worker, th);
        maapi.finishTrans(th);

        // Create utility classes used by the NED
        metaData = new MetaDataModify(this);
        secrets = new NedSecrets(this);
        mCrypto = new MaapiCrypto(maapi);

        // NedCommand default auto-prompts:
        String[][] defaultAutoPrompts = new String[][] {
            { "([!]{10}|[C]{10}|[.]{10})", "<timeout>" },
            { "\\[OK\\]", null },
            { "\\[Done\\]", null },
            { "timeout is \\d+ seconds:", null },  // ping
            { "Error opening \\S+[:]", null }, // copy non-existing file
            { "Key data:", null }, // crypto key export rsa
            { "Cryptochecksum:", null }, // any context FOO write memory
            { "Cryptochecksum \\(changed\\):", null }, //  copy to running
            { "Trustpool import:", null }, // crypto ca trustpool import
            { "INFO:", null }, // key config-key password-encryption + write mem all
            { "\\S+ has the following attributes:", null },
            { "\\S+:\\s*$", "<prompt>" },
            { "\\S+\\][\\?]?\\s*$", "<prompt>" }
        };
        nedCommand = new NedCommand(this, "asa-stats", "asa", EXEC_PROMPT, CONFIG_PROMPT,
                                    "ERROR: [%]", defaultAutoPrompts);

        // Only setup liveStats for connected devices
        if (session != null) {

            // Setup custom show handler
            nedLiveStats.setupCustomShowHandler(new ShowHandler(this, session, NEDLIVESTATS_PROMPT));

            // Make NedLiveStats aware of the ietf-interface and ietf-ip modules.
            nedLiveStats.installParserInfo("if:interfaces-state/interface",
                                           "{'show':'show interface',"+
                                           "'template':'if:interfaces-state_interface.gili',"+
                                           "'show-entry':{'cmd':'show interface %s',"+
                                           "'template':'if:interfaces-state_interface.gili',"+
                                           "'trim-top-node':true,'run-after-show':false}}");

            nedLiveStats.installParserInfo("if:interfaces-state/if:interface/ip:ipv4/ip:address",
                                  "{'show':{'cmd':'show run interface %s | include ip address','arg':['../../name']},"+
                                  "'template':'if:interfaces-state_interface_ip:ipv4_address.gili'}");

            nedLiveStats.installParserInfo("if:interfaces-state/if:interface/ip:ipv6/ip:address",
                                  "{'show':{'cmd':'show run interface %s | include ipv6 address','arg':['../../name']},"+
                                  "'template':'if:interfaces-state_interface_ip:ipv6_address.gili'}");
        }

        logInfo(worker, "DONE SETUP "+tickToString(start));
    }


    /**
     * Get device profile name
     * @param
     * @return device profile name
     */
    private String getDeviceProfile(NedWorker worker, int th) {
        String profile = "cisco-asa";
        try {
            String p = "/ncs:devices/device{"+device_id+"}/device-profile";
            if (maapi.exists(th, p)) {
                profile = ConfValue.getStringByValue(p, maapi.getElem(th, p));
            }
        } catch (Exception ignore) {
            // Ignore Exception
        }
        traceInfo(worker, "device-profile = " + profile);
        return profile;
    }


    /**
     * NedLiveStatsShowHandler
     * @param
     * @throws Exception
     */
    private class ShowHandler extends NedLiveStatsShowHandler {
        private NedComCliBase owner;
        private CliSession session;
        private Pattern[] prompts;

        public ShowHandler(NedComCliBase owner, CliSession session, Pattern[] prompts)
            throws NedLiveStatsException {
            super(owner, session, prompts);
            this.owner = owner;
            this.session = session;
            this.prompts = prompts;
        }

        public String execute(NedWorker worker, String cmd) throws Exception {

            traceInfo(worker, "ShowHandler: "+stringQuote(cmd));

            // '!noop' used for dummy show-entry
            if (cmd.startsWith("!")) {
                return "";
            }

            //TODO: ned-setting cisco-iosxr developer simulate-command *

            // NETSIM show command massage
            if (this.owner != null && this.owner.isNetsim()) {
                // Split interface name
                Pattern p = Pattern.compile("show run interface ([A-Za-z]+)([0-9]+\\S*)");
                Matcher m = p.matcher(cmd);
                if (m.find()) {
                    cmd = cmd.replace(m.group(1)+m.group(2), m.group(1)+" "+m.group(2));
                }

                // Insert "" around the include|exclude <regex>
                String[] args = cmd.split(" [|] (include|exclude) ");
                for (int i = 1; i < args.length; i++) {
                    cmd = cmd.replace(args[i], "\""+args[i]+"\"");
                }
            }

            session.println(cmd);
            session.expect(Pattern.quote(cmd), worker);
            NedExpectResult res = session.expect(prompts, worker);
            return res.getText();
        }
    }


    /*
     **************************************************************************
     * ExtendedApplyException
     **************************************************************************
     */


    /**
     * ExtendedApplyException
     * @param
     */
    private class ExtendedApplyException extends ApplyException {
        public ExtendedApplyException(String line, String msg,
                                      boolean isAtTop,
                                      boolean inConfigMode) {
            super("command: "+line+": "+msg, isAtTop, inConfigMode);
         }
    }


    /*
     **************************************************************************
     * show
     **************************************************************************
     */

    /**
     * Retrieve running config from device
     * @param
     * @throws Exception
     */
    public void show(NedWorker worker, String toptag) throws Exception {

        // Only respond to the first toptag
        if (!toptag.equals("interface")) {
            worker.showCliResponse("");
            return;
        }

        final long start = tick(0);
        if (session != null && trace) {
            session.setTracer(worker);
        }
        logInfo(worker, "BEGIN SHOW");

        // Read config from device
        lastGetConfig = getConfig(worker);

        // Modify config
        String res = modifyInput(worker, lastGetConfig);
        lastTransformedConfig = null;

        // cisco-asa extended-parser
        try {
            if (this.turboParserEnable) {
                traceInfo(worker, "Parsing config using turbo-mode");
                if (parseAndLoadXMLConfigStream(maapi, worker, schema, res)) {
                    // Turbo-parser succeeded, clear config to bypass CLI
                    res = "";
                }
            } else if (this.robustParserMode) {
                traceInfo(worker, "Parsing config using robust-mode");
                res = filterConfig(res, schema, maapi, worker, null, false).toString();
            }

        } catch (Exception e) {
            logError(worker, "extended-parser "+nedSettings.getString(NedSettings.EXTENDED_PARSER)
                     +" exception ERROR: ", e);
            this.turboParserEnable = false;
            this.robustParserMode = false;
        }

        // ned-settings cisco-asa developer delay show
        if (devDelayShow > 0) {
            sleep(worker, devDelayShow, " - developer delay show");
        }

        logInfo(worker, "DONE SHOW "+tickToString(start));
        worker.showCliResponse(res);
    }


    /**
     * Get config from device (all contexts)
     * @param
     * @return
     * @throws Exception
     */
    private String getConfig(NedWorker worker) throws Exception {

        final long start = nedReportProgress(worker, "reading config...", 0);
        try {
            String res = null;

            // Reset timeout
            lastTimeout = setReadTimeout(worker);

            // showOffline
            if (this.offlineData != null) {
                logInfo(worker, "BEGIN get-config (showOffline)");
                res = this.offlineData;
            }

            // Admin user on multi context device
            if (res == null && isAdminContext()) {
                // Get system (context) config
                logInfo(worker, "BEGIN get-config (multi-context)");

                changeto_context(worker, "system", true);
                res = getConfigContext(worker, null, "system");

                // Get 'changeto' contexts config, skip non supported ones
                String ctx = "";
                for (int i = res.indexOf("\ncontext ");
                     i >= 0;
                     i = res.indexOf("\ncontext ", i)) {
                    int nl = res.indexOf('\n', i+1);
                    if (nl < 0) {
                        break;
                    }
                    String context = res.substring(i+9, nl).trim();
                    int supported = isSupportedContext(context);
                    if (supported == 1) {
                        ctx = ctx + getConfigContext(worker, context, context);
                        i = i + 8; // step to next config
                        continue;
                    }
                    int end = res.indexOf("\ncontext ", i+8);
                    if (end < 0) {
                        end = res.indexOf("\n!", i+8); // last block ends with "!"
                    }
                    if (end < 0) {
                        break;
                    }
                    if (supported == -1) {
                        // skip/hide context definition
                        traceInfo(worker, "get-config - ignoring context: /"+context);
                        res = res.substring(0,i) + res.substring(end-1);
                    } else {
                        // keep/include context definition
                        traceInfo(worker, "get-config - ignoring context: /"+context);
                        i += 8;
                    }
                }
                res = res + ctx;
                changeto_context(worker, "system", true);
            }

            // read scp-transfer method
            if (res == null && !"disabled".equals(scpReadMethod)) {
                logInfo(worker, "BEGIN get-config (scp-transfer fallback = "+scpCliFallback+")");
                try {
                    res = scpGetConfig(worker);
                } catch (Exception e) {
                    if ("disabled".equals(scpCliFallback)) {
                        throw e;
                    }
                    logError(worker, "SCP download failed, retrying using CLI method");
                    lastTimeout = setReadTimeout(worker);
                    res = null;
                }
            }

            // Current context
            if (res == null) {
                logInfo(worker, "BEGIN get-config");
                res = getConfigContext(worker, null, "current");
            }

            logInfo(worker, "DONE get-config ["+res.length()+" bytes] "+tickToString(start));
            nedReportProgress(worker, "reading config ok", start);
            return res;

        } catch (Exception e) {
            nedReportProgress(worker, "reading config error", start);
            throw e;
        }
    }


    /**
     * Get config from device (single context)
     * @param
     * @return
     * @throws Exception
     */
    private String getConfigContext(NedWorker worker, String context, String ctxname) throws Exception {
        final long start = tick(0);

        // devices device <name> live-status exec any "sync-from-file <syncFile>"
        String res;
        if (isNetsim() && syncFile != null) {
            logInfo(worker, "BEGIN get-config /"+ctxname+" (file = "+syncFile+")");
            res = print_line_exec(worker, "file show " + syncFile);
            if (res.contains("Error: failed to open file")) {
                throw new NedException("failed to sync from file " + syncFile);
            }
        }

        // Multi-context user context with specified admin-device
        else if (contextName != null && adminDeviceName != null) {
            logInfo(worker, "BEGIN get-config /"+ctxname+" ("+adminDeviceMethod+" admin-device)");
            res = getConfigFromAdmin(worker);
        }

        // Multi-context admin showing context config => use more command
        else if (context != null && writeMemoryMode.startsWith("on-commit")) {
            logInfo(worker, "BEGIN get-config /"+ctxname+" (context more)");
            res = getConfigUsingMore(worker, context);
        }

        // NETSIM
        else if (isNetsim()) {
            logInfo(worker, "BEGIN get-config /"+ctxname+" (NETSIM)");
            res = print_line_exec(worker, "show running-config");
        }

        // System context or single mode device
        else {
            logInfo(worker, "BEGIN get-config /"+ctxname+" (system more)");
            if (context != null && isAdminContext()) {
                changeto_context(worker, context, true);
            }
            res = print_line_exec_slow(worker, "more system:running-config", 0);
            if (isExecError(res)) {
                res = null;
            }
        }

        // Fallback to show running-config
        if (res == null) {
            logInfo(worker, "BEGIN get-config /"+ctxname+" (show run fallback)");
            if (context != null) {
                changeto_context(worker, context, true);
            }
            res = print_line_exec_slow(worker, "show running-config", 0);
        }

        // Reset timeout to give more time to parse input
        lastTimeout = setReadTimeout(worker);

        // Strip beginning
        int i = res.indexOf(": Hardware:");
        if (i >= 0) {
            int nl = res.indexOf('\n', i);
            res = res.substring(nl+1);
        }

        // Strip ': end' and all text after
        i = res.lastIndexOf("\n: end");
        if (i >= 0) {
            res = res.substring(0,i);
        }
        res = res.trim() + "\n";

        // Insert context name
        if (context != null && contextName == null) {
            res = "changeto context "+context+"\n" + res;
        }

        logInfo(worker, "DONE get-config /"+ctxname+" "+tickToString(start));
        return res;
    }


    /**
	 * getConfigFromAdmin  - This method gets configuration from admin session
     *      ASA currently does not support display of non-obfuscated running configuration
     *      for a user context. Only way to obtain non-obfuscated configuration is from
     *      system context by displaying startup configuration, which is saved as configUrl file.
     *      Consequently:
     *      - The NED setting 'read use-startup-config' must be set to true for contexts (method throws exception)
     *      - The NED setting 'write memory-setting' should be enabled.  This method does
     *        not check for this, but if configuration is not saved after modification, next attempt to display
     *        will result in exception due to non-matching checksums of running and startup configurations.
     *
     *      NED setting 'read transaction-id-method'
     *          For the multi-context device where context are managed directly, value 'show-checksum' is most
     *          efficient. If in this situation the 'config-hash' is specified (which is default), the configuration
     *          needs to be obtained twice for each commit before and after for the purpose of calculating checksum.
     *          Each time configuration is obtained the session to admin context gets to be used, which is the ASA
     *          management bottle neck - only 5 SSH sessions supported by admin context, in contrast the number of
     *          user contexts can be up to 250.
     *
     *          Use of 'config-hash' allows checksum to be obtained without using admin session twice.
     *
	 * @param worker
     *      NedWorker
	 * @return
     *      Configuration of user context retrieved from admin device
	 * @throws Exception
     *      When configuration can not be successfully obtained
	 */
    private String getConfigFromAdmin(NedWorker worker) throws Exception {

        String cmd = "more " + configUrl;
        if (isNetsim()) {
            cmd = "show run";
        }

        // See if config is saved (by checksum comparison) to see if we can use admin-device
        String runCryptochecksum = getCryptochecksum(session, worker);
        if (runCryptochecksum == null) {
            traceInfo(worker, "NOTICE: auto-saving modified running-config for context /"+this.contextName);
            print_line_wait_oper(worker, "write memory");
        }

        traceInfo(worker, "admin-device: "+adminDeviceMethod.toUpperCase()+" "+adminDeviceName
                  +" getting /"+contextName+" context config");

        //
        // cisco-asa admin-device method ssh
        //
        String res;
        if (adminDeviceMethod.startsWith("ssh")) {
            setupAdminSSH(worker, this.adminDeviceName, -1);
            res = adminSSH.print_line_wait(worker, device_id, cmd);
            if (adminDeviceMethod.equals("ssh")) {
                closeAdminSSH(worker);
            }
        }

        //
        // cisco-asa admin-device method maapi
        //
        else {
            // Get and wait on semaphore for restricted parallel access
            Semaphore semaphore;
            int queueLength;
            synchronized (adminSem) {
                semaphore = adminSem.get(adminDeviceName);
                if (semaphore == null) {
                    logVerbose(worker, "admin-device: MAAPI "+adminDeviceName
                               +" creating semaphore, permits = "+adminDeviceMaxSessions);
                    semaphore = new Semaphore(adminDeviceMaxSessions, true);
                    adminSem.put(adminDeviceName, semaphore);
                }
                queueLength = semaphore.getQueueLength();
            }
            int timeout = (queueLength + 1) * readTimeout;
            logVerbose(worker, "admin-device: MAAPI "+adminDeviceName+" resetting timeout to "+timeout);
            worker.setTimeout(timeout);
            semaphore.acquire();
            logVerbose(worker, "admin-device: MAAPI "+adminDeviceName+" LOCKED");

            // Get config from admin-device using NAVU action
            NavuContext context = null;
            try {
                boolean userSessionExists = checkIfUserSessionExists();
                if (!userSessionExists) {
                    traceInfo(worker, "admin-device: MAAPI "+adminDeviceName+" opening new user session");
                    maapi.startUserSession("admin", InetAddress.getLocalHost(), "maapi",
                                        new String[] { "ncsadmin" },
                                        MaapiUserSessionFlag.PROTO_TCP);
                } else {
                    traceInfo(worker, "admin-device: MAAPI "+adminDeviceName+" reusing existing user session");
                }

                int th = maapi.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);
                context = new NavuContext(maapi, th);
                NavuContainer root = new NavuContainer(context);
                String actionStr = "ncs:devices/ncs:device{%s}/live-status/asa-stats:exec/any";
                ConfPath actionConfPath = new ConfPath(actionStr, adminDeviceName);
                NavuNode actionNode = root.getNavuNode(actionConfPath);

                if (actionNode instanceof NavuAction) {
                    ConfXMLParam[] input = new ConfXMLParam[2];
                    input[0] = new ConfXMLParamValue("asa-stats", "context", new ConfBuf("system"));
                    ConfList list = new ConfList();
                    list.addElem(new ConfBuf(cmd));
                    input[1] = new ConfXMLParamValue("asa-stats", "args", list);

                    NavuAction action = (NavuAction) actionNode;
                    ConfXMLParam[] result = null;
                    traceInfo(worker, "admin-device: MAAPI "+adminDeviceName
                              +" dispatching action to get /"+contextName+" context config");
                    result = action.call(input);
                    maapi.finishTrans(th);
                    if (!userSessionExists) {
                        logVerbose(worker, "admin-device: MAAPI "+adminDeviceName+" closing user session");
                        maapi.endUserSession();
                    }
                    res = ((ConfXMLParamValue) result[0]).getValue().toString();
                } else {
                    throw new NedException("admin-device: MAAPI "+adminDeviceName
                                           +" failed to get action node '"+actionStr+"'");
                }
            } finally {
                logVerbose(worker, "admin-device: MAAPI "+adminDeviceName+" UNLOCKED");
                semaphore.release();
                if (context != null) {
                    context.removeCdbSessions();
                }
            }
        }

        // Check for errors
        if (res == null) {
            throw new NedException("admin-device: Failed to get saved configuration from /"
                                   +contextName+" via "+adminDeviceName);
        }
        if (isExecError(res)) {
            throw new NedException("admin-device: Failed to get saved configuration from /"
                                   +contextName+" via "+adminDeviceName+" :: " + res);
        }

        // Verify saved-config Cryptochecksum vs running
        if (runCryptochecksum == null) {
            runCryptochecksum = getRunCryptochecksum(session, worker);
        }
        String dskCryptochecksum = getDskCryptochecksum(session, worker, res, true);
        if (!dskCryptochecksum.equals(runCryptochecksum)) {
            throw new NedException("The running-config " + runCryptochecksum
                                   + " and startup-config " + dskCryptochecksum + " checksum did not match");
        }

        traceInfo(worker, "admin-device: "+adminDeviceName+" obtained "+res.length()
                  +" bytes of /"+contextName+" config");
        traceInfo(worker, "\nSHOW_ADMIN:\n'"+res+"'");
        return res;
    }


	/**
	 * checkIfUserSessionExists  - This method checks if MAAPI has user session
     *
	 * @return
     *      true when session exists, false otherwise
	 */
    private boolean checkIfUserSessionExists() {
        int userSession = 0;
        try {
            userSession = maapi.getMyUserSession();
        } catch (MaapiException e) {
            // user session does not exist
            return false;
        } catch (ConfException e) {
            // got a ConfException, so fail here
            return false;
        } catch (IOException e) {
        	// got an IOException, so fail here
            return false;
        }
        return (userSession != 0) ? true : false;
    }


    /**
     * Download running-config from device using SCP
     * @param
     * @return
     * @throws Exception
     */
    private String scpGetConfig(NedWorker worker) throws Exception {
        String dir = scpDirectory();
        String file = scpFile(worker, false);
        try {
            // Copy running config to temporary file
            scpCopyRunningConfigToFile(worker, file);

            // Open SCP connection and download the copy of running-config
            String res = "";
            String fallbackAddress = scpOpenSession(worker, null, "download");
            try {
                res = scpSession.get(this, worker, file, dir);
            } catch (Exception e) {
                if (fallbackAddress == null) {
                    throw e;
                }
                traceInfo(worker, "SCP download Exception triggering fallback: "+e.getMessage());

                // Fallback and try download from the other physical device
                traceInfo(worker, "SCP download from "+this.scpAddress+" failed, retrying using "+fallbackAddress);
                fallbackAddress = scpOpenSession(worker, fallbackAddress, "fallback download");
                res = scpSession.get(this, worker, file, dir);
            }

            return res;

        } catch (Exception e) {
            traceInfo(worker, "SCP download Exception: "+e.getMessage());
            throw new NedException("SCP download ["+dir+file+"] ERROR: "+e.getMessage(), e);

        } finally {
            scpFinally(worker, file);
        }
    }


    /**
     * Copy running-config to file
     * @param NedWorker
     * @throws Exception
     */
    private void scpCopyRunningConfigToFile(NedWorker worker, String file) throws Exception {
        String res = print_line_exec(worker, "copy /noconfirm running-config "+file);
        if (isExecError(res)) {
            res = stripLineAll(worker, res, "Cryptochecksum ", false);
            res = stripLineAll(worker, res, " bytes copied in ", true);
            throw new NedException(res);
        }
    }


    /**
     *
     * @param
     * @return
     * @throws Exception
     */
    private String getConfigUsingMore(NedWorker worker, String context) throws Exception {

        // Change to context
        if (!changeto_context(worker, context, false)) {
            // Ignore 'ERROR: Context hasn't been initialized with 'config-url''
            return "";
        }

        // Get running-config Cryptochecksum, auto-save if using startup-config
        String runCryptochecksum = getRunCryptochecksum(session, worker);
        String dskCryptochecksum = getDskCryptochecksum(session, worker, null, false);
        if (!dskCryptochecksum.equals(runCryptochecksum)) {
            traceInfo(worker, "NOTICE: auto-saving modified running-config for context /"+context);
            print_line_wait_oper(worker, "write memory");
        }

        // Change to system
        changeto_context(worker, "system", true);

        // Get filename for saved running-config
        String res = print_line_exec(worker, "show run context "+context+" | i config-url");
        String filename;
        if (res != null && (filename = getMatch(res.trim(), "config-url (\\S+)")) != null) {
            traceInfo(worker, "Using config-url "+filename+" for saved running-config");
        } else {
            traceInfo(worker, "Using context name /"+context+" for saved running-config");
            filename = "disk0:/"+context+".cfg";
        }

        // Get saved-config using more command in system context
        res = print_line_exec_slow(worker, "more "+filename, 0);
        if (isExecError(res)) {
            traceInfo(worker, "ERROR: failed to show config with 'more "+filename+"'");
            return null;
        }

        // cisco-asa read use-startup-config
        if (useStartupConfig) {
            traceInfo(worker, "read/use-startup-config = true, skipping checksum comparison for context "+context);
            return res;
        }

        // Verify that Cryptochecksum match. If they do, we can use the config from the more command
        dskCryptochecksum = getDskCryptochecksum(session, worker, res, false);
        if (dskCryptochecksum.equals(runCryptochecksum)) {
            traceInfo(worker, "Cryptochecksum match, using saved config from context /"+context);
            return res;
        }

        traceInfo(worker, "WARNING: running-config modified for context /"+context+", can't use more command");
        return null;
    }


	/**
     * Check if context is supported
     * @param
	 * @return -1 not found, 0 definition only, 1 definition and config
     */
    private int isSupportedContext(String context) {
        if (contextList.isEmpty()) {
            return 1; // no entries, everything accepted
        }
        for (int n = 0; n < contextList.size(); n++) {
            String[] entry = contextList.get(n);
            if (findString(entry[0], context) >= 0) {
                if (entry[1].contains("hide-cfg")) {
                    return 0;
                }
                return 1;
            }
        }
        return -1;
    }


    /**
     * Modify input
     * @param
     * @return
     * @throws Exception
     */
    private String modifyInput(NedWorker worker, String res) throws Exception {
        final long start = tick(0);
        logInfo(worker, "BEGIN in-transforming");

        // Split into context(s)
        String[] lines = res.split("\n");
        StringBuilder sb = new StringBuilder();
        List<String> parts = new ArrayList<>();
        for (int n = 0; n < lines.length; n++) {
            if (lines[n].startsWith("changeto context ") && sb.length() > 0) {
                parts.add(sb.toString());
                sb = new StringBuilder();
            }
            sb.append(lines[n]+"\n");
        }
        if (sb.length() > 0) {
            parts.add(sb.toString());
        }

        // Modify input per context
        sb = new StringBuilder();
        for (String dump : parts) {
            String context = null;
            dump = dump.trim() + "\n";
            if (dump.startsWith("changeto context ")) {
                context = getMatch(dump, "changeto context\\s+(\\S+)");
            }
            dump = modifyInputContext(worker, context, dump);
            if (context != null) {
                // Context config, add lines indented 1 space for turbo/robust mode
                lines = dump.trim().split("\n");
                sb.append(lines[0]+"\n"); // don't indent changeto context line
                for (int n = 1; n < lines.length; n++) {
                    sb.append(" "+lines[n]+"\n");
                }
                sb.append(" \"changeto system\"\n");  // exit command
            } else {
                sb.append(dump);
            }
        }
        res = sb.toString();

        // Update secrets - replace encrypted secrets with cleartext if not changed
        traceVerbose(worker, "in-transforming - updating all secrets");
        res = secrets.update(worker, res, false);

        traceVerbose(worker, "\nSHOW_AFTER:\n"+res);
        logInfo(worker, "DONE in-transforming "+tickToString(start));
        return res;
    }


    /**
     * Transform input config (single context)
     * @param
     * @return
     * @throws Exception
     */
    private String modifyInputContext(NedWorker worker, String context, String res) throws Exception {

        // NETSIM - return early
        if (isNetsim() && syncFile == null && offlineData == null) {
            //  Add ssh key-exchange group default:
            res += "ssh key-exchange group dh-group1-sha1\n";

            return res;
        }

        String myContext = context != null ? context : "system";
        traceInfo(worker, "in-transforming /"+myContext+" config");

        //
        // LINE-BY-LINE INPUT TRANSFORMATIONS
        //
        String match;
        String toptag = "";
        String[] lines = res.split("\n");
        StringBuilder sb = new StringBuilder();
        HashMap<String,String> traceMap = new HashMap<>();
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // Update toptag
            if (isTopExit(lines[n])) {
                toptag = "";
            } else if (Character.isLetter(lines[n].charAt(0))) {
                toptag = trimmed; // strip \r
            }

            String input = null;
            boolean split = false;

            //
            // tcp-map / tcp-options range
            //
            if (toptag.startsWith("tcp-map ") && trimmed.startsWith("tcp-options range ")) {
                Pattern p = Pattern.compile("tcp-options range (\\d+) (\\d+) (\\S+)");
                Matcher m = p.matcher(trimmed);
                if (m.find()) {
                    int start = Integer.parseInt(m.group(1));
                    int end = Integer.parseInt(m.group(2));
                    if (start == end) {
                        input = " tcp-options range "+start+" "+m.group(3);
                    } else {
                        split = true;
                        for (int i = start; i <= end; i++) {
                            sb.append(" tcp-options range "+i+" "+m.group(3)+"\n");
                        }
                    }
                }
            }

            //
            /// access-list *
            //
            else if (trimmed.startsWith("access-list ")) {
                // String quote the rule only
                Pattern p = Pattern.compile("(access-list \\S+ )((?:extended|webtype) [ \\S]+?)( (log|log .+|time-range .+|inactive))$");
                Matcher m = p.matcher(trimmed);
                String acllist = null;
                if (m.find()) {
                    String quoted = stringQuote(m.group(2).trim());
                    input = m.group(1) + quoted + m.group(3);
                    if (input.contains(" log disable")) {
                        input = input.replace(" log disable", "");
                    }
                    acllist = m.group(1);
                } else {
                    p = Pattern.compile("(access-list \\S+ )((extended|standard|remark|ethertype|webtype) .*)");
                    m = p.matcher(trimmed);
                    if (m.find()) {
                        if (m.group(3).equals("remark")) {
                            // Patch duplicate remarks with '[<index>]' to be read uniquely by NSO
                            int remarkindex = 2;
                            for (int d = n + 1; d < lines.length; d++) {
                                String duplicate = lines[d].trim();
                                if (!duplicate.startsWith(m.group(1))) {
                                    break;
                                }
                                if (duplicate.equals(trimmed)) {
                                    lines[d] = duplicate + " [["+(remarkindex++)+"]]";
                                    traceInfo(worker, "transformed <= patched '"+duplicate+"' to '"+lines[n]+"'");
                                }
                            }
                        }
                        String quoted = stringQuote(m.group(2).trim());
                        input = m.group(1) + quoted;
                        acllist = m.group(1);
                    }
                }

                // Only log the first rule transformation, to save trace space for huge lists
                if (acllist != null && input != null && devTraceLevel < TRACE_DEBUG2) {
                    sb.append(input+"\n");
                    if (traceMap.get(acllist) == null) {
                        traceVerbose(worker, "transformed <= quoted all '"+acllist+"' rules");
                        traceMap.put(acllist, "traced");
                    }
                    continue;
                }
            }

            //
            // banner exec|login|motd
            // group-policy * attributes / banner value
            //
            else if ((toptag.startsWith("banner ") || toptag.startsWith("group-policy "))
                     && !"banner none".equals(trimmed)
                     && (match = getMatch(trimmed, "banner (\\S+)")) != null) {
                String banner = null;
                for (; n < lines.length; n++) {
                    if (!lines[n].trim().startsWith("banner "+match)) {
                        n--; // look at this line again in outer for-loop
                        break;
                    }
                    String text = lines[n].replace("\r","").replaceFirst("[ ]?banner "+match+" ", "");
                    if (banner == null) {
                        banner = text;
                    } else {
                        banner += ("\r\n" + text); // Because last CRLF is stripped
                    }
                }
                sb.append("banner "+match+" "+stringQuote(banner)+"\n");
                traceVerbose(worker, "transformed <= quoted 'banner "+match+"' text");
                continue;
            }

            //
            // as-path access-list
            //
            else if (trimmed.startsWith("as-path access-list ")
                     && (match = getMatch(trimmed, "as-path access-list \\d+ ((?:permit|deny) .+)")) != null) {
                input = line.replace(match, stringQuote(match));
            }

            //
            // icmp *
            //
            else if (toptag.startsWith("icmp ") && trimmed.startsWith("icmp ")) {
                input = line.replaceAll("icmp ((?:deny|permit) .+) (\\S+)", "icmp $2 $1");
            }

            //
            // ipv6 icmp *
            //
            else if (toptag.startsWith("ipv6 icmp ") && trimmed.startsWith("ipv6 icmp ")) {
                input = line.replaceAll("ipv6 icmp ((?:deny|permit) .+) (\\S+)", "ipv6 icmp $2 $1");
            }

            //
            // fragment *
            //
            else if (toptag.startsWith("fragment ") && trimmed.startsWith("fragment ")) {
                input = line.replaceAll("fragment (\\S+) (\\S+) (\\S+)", "fragment $3 $1 $2");
            }

            //
            // snmp-server user * [SECRETS]
            //
            else if (line.startsWith("snmp-server user ")) {
                input = line.replace(" encrypted auth ", " auth ");
            }

            //
            // monitor-interface *
            // no monitor-interface *
            //
            else if (toptag.startsWith("monitor-interface ") && trimmed.startsWith("monitor-interface ")) {
                input = line.replaceAll("monitor-interface (\\S+)", "monitor-interface $1 enable");
            } else if (line.startsWith("no monitor-interface ")
                       && !trimmed.contains("monitor-interface service-module")) {
                input = line.replaceAll("no monitor-interface (\\S+)",
                                            "monitor-interface $1 disable");
            }

            //
            // transform single lines
            //
            else if (trimmed.startsWith("no passive-interface ")) {
                input = line.replace("no passive-interface ", "disable passive-interface ");
            }

            //
            // transform no-list lists/leaves
            //
            if (trimmed.startsWith("no logging message ")) {
                input = line.replace("no logging message ", "logging message no-list ");
            } else if (trimmed.startsWith("no service resetoutbound interface ")) {
                input = line.replace("no service resetoutbound interface ",
                                         "service resetoutbound interface no-list ");
            }

            //
            // strip single lines
            //
            else if (trimmed.equals("boot-start-marker") || trimmed.equals("boot-end-marker")) {
                input = "";
            } else if (trimmed.startsWith("alias ")) {
                input = "";
            } else if (trimmed.startsWith(":")) {
                input = "";
                if (devTraceLevel < TRACE_DEBUG2) {
                    continue; // Silent trimming
                }
            } else if (trimmed.startsWith("ASA Version")) {
                input = "";
            } else if (trimmed.startsWith("hw-module")) {
                input = "";
            } else if (trimmed.startsWith("diagnostic")) {
                input = "";
            } else if (trimmed.startsWith("macro name")) {
                input = "";
            } else if (trimmed.startsWith("ntp clock-period")) {
                input = "";
            } else if (trimmed.startsWith("terminal width ")) {
                input = "";
            } else if (trimmed.startsWith("Cryptochecksum:")) {
                input = "";
            } else if (trimmed.startsWith("> more ")) {
                input = "";
            }

            //
            // Append line or log lines
            //
            if (split) {
                traceVerbose(worker, "transformed <= split '"+trimmed+"'");
            } else if (input != null && !input.equals(lines[n])) {
                if (input.isEmpty()) {
                    traceVerbose(worker, "transformed <= stripped '"+trimmed+"'");
                } else {
                    traceVerbose(worker, "transformed <= '"+trimmed+"' to '"+input.trim()+"'");
                    sb.append(input+"\n");
                }
            } else {
                sb.append(lines[n]+"\n");
            }
        }

        // Add hidden DefaultDNS server-group
        traceVerbose(worker, "transformed <= injected 'dns server-group DefaultDNS' in /"+myContext);
        sb.append("dns server-group DefaultDNS\nexit\n");

        // Restore single buffer
        res = "\n" + sb.toString();

        //
        // Look for crypto certificate(s) and quote contents
        //
        traceVerbose(worker, "in-transforming - quoting /"+myContext+" certificates");
        int i = res.indexOf("\n certificate ");
        while (i >= 0) {
            int start = res.indexOf('\n', i+1);
            traceVerbose(worker, "transformed <= quoted cert '"+res.substring(i,start).trim()+"'");
            if (start > 0) {
                int end = res.indexOf("quit", start);
                if (end > 0) {
                    String cert = res.substring(start+1, end);
                    res = res.substring(0,start+1) + stringQuote(cert)
                        + "\n" + res.substring(end);
                }
            }
            i = res.indexOf("\n certificate ", i+14);
        }

        // Respond with updated show buffer
        //if (context != null) { traceVerbose(worker, "\nSHOW_AFTER_"+context+":\n"+res); }
        return res;
    }


    /**
     *
     * @param
     * @return
     * @throws Exception
     */
    private String getRunCryptochecksum(CliSession tsession, NedWorker worker)
        throws IOException, SSHSessionException {
        String res;
        int index;

        // NETSIM debugging
        if (isTrueNetsim()) {
            res = print_line_exec(tsession, worker, "show confd-state internal cdb datastore running transaction-id");
            if (res.contains("syntax error")) {
                return null;
            }
            index = res.indexOf("transaction-id ");
        }

        // Real ASA device
        else {
            res = print_line_exec(tsession, worker, "show checksum");
            if (isExecError(res)) {
                return null;
            }
            index = res.indexOf("Cryptochecksum:");
        }

        if (index < 0) {
            return null;
        }
        res = res.substring(index).replace(" ", "").trim();
        traceVerbose(worker, "Cryptochecksum(run) = "+res);
        return res;
    }


    /**
     *
     * @param
     * @return
     * @throws Exception
     */
    private String getDskCryptochecksum(CliSession tsession, NedWorker worker, String config, boolean throwException)
        throws NedException, IOException, SSHSessionException {
        String res;

        // NETSIM debugging
        if (isTrueNetsim()) {
            res = getRunCryptochecksum(tsession, worker); // dsk checksum same as running
        }

        // Real device
        else {
            if (config == null) {
                config = print_line_exec(tsession, worker, "show startup-config | i ^Cryptochecksum");
            }
            res = findLine(config, "Cryptochecksum:");
        }

        if (res == null) {
            if (throwException) {
                throw new NedException("Failed to find Cryptochecksum in startup-config");
            }
            return "failed-to-find-dsk-Cryptochecksum";
        }
        res = res.trim();
        traceVerbose(worker, "Cryptochecksum(dsk) = "+res);
        return res;
    }


    /**
     *
     * @param
     * @return
     * @throws Exception
     */
    private String getCryptochecksum(CliSession tsession, NedWorker worker)
        throws NedException, IOException, SSHSessionException {
        String runCryptochecksum = getRunCryptochecksum(tsession, worker);
        if (runCryptochecksum == null) {
            throw new NedException("Failed to get 'show checksum'");
        }
        String dskCryptochecksum = getDskCryptochecksum(tsession, worker, null, true);
        if (dskCryptochecksum.equals(runCryptochecksum)) {
            return runCryptochecksum;
        }
        return null;
    }


    /*
     **************************************************************************
     * showOffline
     **************************************************************************
     */

    /**
     * Parse and input given config
     * @param
     * @throws Exception
     */
    // @Override
    public void showOffline(NedWorker worker, String toptag, String data) throws Exception {
        try {
            logInfo(worker, "BEGIN SHOW-OFFLINE");
            this.offlineData = data;
            show(worker, toptag);
            logInfo(worker, "DONE SHOW-OFFLINE");
        } finally {
            this.offlineData = null;
        }
    }


    /*
     **************************************************************************
     * showPartial
     **************************************************************************
     */

    /**
     * Retrieve partial running config from device
     * @param
     * @throws Exception
     */
    @Override
    public void showPartial(NedWorker worker, String[] cmdpaths) throws Exception {
        final long start = tick(0);
        logInfo(worker, "BEGIN SHOW-PARTIAL String[]");
        showPartialInternal(schema, maapi, turboParserEnable, worker, cmdpaths);
        logInfo(worker, "DONE SHOW-PARTIAL "+tickToString(start));
    }


    /**
     * Retrieve partial running config from device
     * @param
     * @throws Exception
     */
    @Override
    public void showPartial(NedWorker worker, ConfPath[] paths) throws Exception {
        final long start = tick(0);
        logInfo(worker, "BEGIN SHOW-PARTIAL ConfPath[]");
        showPartialInternal(schema, maapi, turboParserEnable, worker, paths);
        logInfo(worker, "DONE SHOW-PARTIAL "+tickToString(start));
    }


    /*
     **************************************************************************
     * getDeviceConfiguration
     **************************************************************************
     */

    /**
     * Get device configuration
     * @param
     * @return
     * @throws Exception
     */
    @Override
    protected String getDeviceConfiguration(NedWorker worker) throws Exception {
        String config = getConfig(worker);
        return modifyInput(worker, config);
    }


    /*
     **************************************************************************
     * getTransId
     **************************************************************************
     */

    /**
     * Calculate transaction-id
     * @param
     * @throws Exception
     */
    public void getTransId(NedWorker worker) throws Exception {
        final long start = tick(0);
        if (trace) {
            session.setTracer(worker);
        }

        //
        // Use last cached transformed config from applyConfig() secret code
        //
        String res;
        if (transActionIdMethod.startsWith("config-hash") && lastTransformedConfig != null) {
            logInfo(worker, "BEGIN GET-TRANS-ID (config-hash secrets)");
            res = lastTransformedConfig;
            lastGetConfig = null;
            lastTransformedConfig = null;
        }

        //
        // read transaction-id-method config-hash-cached
        //
        else if ("config-hash-cached".equals(transActionIdMethod) && lastGetConfig != null) {
            logInfo(worker, "BEGIN GET-TRANS-ID (config-hash-cached");
            res = modifyInput(worker, lastGetConfig);
            lastGetConfig = null;
        }

        //
        // read transaction-id-method show-checksum
        //
        else if ("show-checksum".equals(transActionIdMethod)
                 && isDevice()
                 && isSingleContext()) {
            logInfo(worker, "BEGIN GET-TRANS-ID (show-checksum)");
            res = getRunCryptochecksum(session, worker);
            if (res != null) {
                logInfo(worker, "DONE GET-TRANS-ID ("+res+") "+tickToString(start));
                worker.getTransIdResponse(res);
                return;
            }
        }

        //
        // read transaction-id-method
        //
        else {
            logInfo(worker, "BEGIN GET-TRANS-ID ("+transActionIdMethod+")");
            String config = getConfig(worker);
            res = modifyInput(worker, config);
        }

        // Get configuration to calculate checksum
        if (res == null) {
            logInfo(worker, "BEGIN GET-TRANS-ID ("+transActionIdMethod+" fallback)");
            String config = getConfig(worker);
            res = modifyInput(worker, config);
        }
        worker.setTimeout(readTimeout);

        res = stripLineAll(worker, res, "failover lan unit ", false);
        res = res.trim();

        traceVerbose(worker, "TRANS-ID-BUF=\n+++ begin\n"+res+"\n+++ end");

        // Calculate checksum of running-config
        byte[] bytes = res.getBytes("UTF-8");
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] thedigest = md.digest(bytes);
        BigInteger md5Number = new BigInteger(1, thedigest);
        String md5String = md5Number.toString(16);

        logInfo(worker, "DONE GET-TRANS-ID ("+md5String+") "+tickToString(start));
        worker.getTransIdResponse(md5String);
    }


    /*
     **************************************************************************
     * prepareDry
     **************************************************************************
     */

    /**
     * Display config for commit dry-run.
     * WARNING: read-timeout = write-timeout = connect-timeout = 0
     * @param
     * @throws Exception
     */
    public void prepareDry(NedWorker worker, String data) throws Exception {
        final long start = tick(0);
        if (trace && session != null) {
            session.setTracer(worker);
            traceVerbose(worker, data);
        }

        String log = "BEGIN PREPARE-DRY (model="+asamodel+" version="+asaversion+" bytes="+data.trim().length()+")";
        if (session == null) {
            log += " offline";
            if (configSessionMode.contains("enabled") && asaversion.contains(" <ses>")) {
                haveConfigSession = true;
            }
        }
        logInfo(worker, log);
        if (session == null) {
            traceInfo(worker, "read-timeout "+readTimeout+" write-timeout "+writeTimeout);
        }

        // ShowRaw used in debugging, to see cli commands before modification
        if (showRaw || data.contains("tailfned raw-run\n")) {
            showRaw = false;
            logInfo(worker, "DONE PREPARE-DRY (raw)"+tickToString(start));
            worker.prepareDryResponse(data);
            return;
        }

        // Attach to CDB
        int fromTh = worker.getFromTransactionId();
        int toTh = worker.getToTransactionId();
        maapiAttach(worker, fromTh, toTh);

        // Modify data
        try {
            data = modifyOutput(worker, data, fromTh, toTh, true);
            String[] lines = data.split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (!logVerbose && line.trim().startsWith(META_DATA)) {
                    continue;
                }
                sb.append(line+"\n");
            }
            data = sb.toString();

            // Show which config goes into session
            if (data.length() < scpWriteThreshold) {
                data = sessionPrepareDry(worker, data, toTh);
            }

            if (session == null && logVerbose) {
                data = "! Generated offline\n" + data;
            }
        } finally {
            maapiDetach(worker, fromTh, toTh);
        }

        logInfo(worker, "DONE PREPARE-DRY"+tickToString(start));
        worker.prepareDryResponse(data);
    }


    /**
     *
     * @param
     * @return
     * @throws Exception
     */
    private String sessionPrepareDry(NedWorker worker, String data, int toTh) throws NedException {

        if (!configSessionMode.contains("enabled")) {
            return data;
        }
        if (!haveConfigSession) {
            return data;
        }
        if (isAdminContext()) {
            // FIXME: no support for config sessions in multiple contexts yet
            return data;
        }

        // Split config into [0] = first, [1&2] = session, [3] = last
        StringBuilder[] sb = sessionModifyData(worker, data, toTh);
        data = "\n" + sb[0].toString();
        for (int n = 1; n < 3; n++) {
            if (sb[n].length() > 0) {
                data += "configure session nso\n";
                data += sb[n].toString();
                data += "! exit-session-nso\n";
            }
        }
        data += sb[3].toString();
        return data;
    }


    /*
     **************************************************************************
     * applyConfig
     **************************************************************************
     *
     * NSO PHASES:
     *          prepare (send data to device)
     *           /   \
     *          v     v
     *       abort | commit (send confirmed commit)
     *               /   \
     *              v     v
     *          revert | persist (send confirming commit)
     */

    /**
     * Apply config. Note: NSO uses connect-timeout for this method.
     * @param

     * @throws Exception
     */
    @Override
    public void applyConfig(NedWorker worker, int cmd, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {
        final long start = tick(0);
        if (trace) {
            session.setTracer(worker);
        }
        logInfo(worker, "BEGIN APPLY-CONFIG (bytes="+data.trim().length()+")");

        // Apply the commit
        doApplyConfig(worker, cmd, data);

        // ned-settings cisco-asa developer delay apply
        if (devDelayApply > 0) {
            sleep(worker, devDelayApply, " - developer delay apply");
        }

        logInfo(worker, "DONE APPLY-CONFIG "+tickToString(start));
    }


    /**
     * Modify and send config to device
     * @param
     * @throws NedException, IOException, SSHSessionException, ApplyException
     */
    private void doApplyConfig(NedWorker worker, int cmd, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {

        // Clear cached states
        lastGetConfig = null;
        lastTransformedConfig = null;
        modifiedContextList = null;
        lastTransactionId = worker.getToTransactionId();

        // Nothing to send
        if (data.trim().isEmpty()) {
            traceInfo(worker, "Empty data, nothing to send");
            return;
        }

        // Attach to CDB
        int fromTh = worker.getFromTransactionId();
        int toTh = worker.getToTransactionId();
        maapiAttach(worker, fromTh, toTh);

        //
        // Modify data
        //
        try {
            data = modifyOutput(worker, data, fromTh, toTh, false);
            traceVerbose(worker, "APPLY_AFTER:\n"+data);
        } catch (Exception e) {
            maapiDetach(worker, fromTh, toTh);
            throw e;
        }

        //
        // Send data
        //
        final long start = nedReportProgress(worker, "sending config...", 0);
        try {
            lastTimeout = setReadTimeout(worker);
            boolean uploaded = false;
            String tdata = "\n" + data;
            if (data.length() >= scpWriteThreshold
                && !tdata.contains("\nsla monitor ")) { // FIXME: Dirty patch, fix with CDB lookup?
                // Send data using SCP
                uploaded = scpSendConfig(worker, cmd, data);
            }

            // Reconnect to device if remote end closed connection due to being idle
            if (session.serverSideClosed()) {
                reconnectDevice(worker);
            }

            //
            // Not uploaded by SCP, use CLI to set config on device
            //
            if (!uploaded) {

                // Send data in config session & terminal
                if (configSessionMode.contains("enabled")
                    && haveConfigSession
                    && !isAdminContext()) {
                    // FIXME: support for multiple context session commits

                    // Split config into [0] = first, [1&2] = session, [3] = last
                    StringBuilder[] sb = sessionModifyData(worker, data, toTh);

                    // Send first data
                    if (sb[0].length() > 0) {
                        sendConfig(worker, cmd, sb[0].toString());
                    }

                    // Send session data
                    for (int n = 1; n < 3; n++) {
                        if (sb[n].length() > 0) {
                            sessionSendConfig(worker, cmd, sb[n].toString());
                        }
                    }

                    // Send last data
                    if (sb[3].length() > 0) {
                        sendConfig(worker, cmd, sb[3].toString());
                    }
                }

                // Send data in config terminal
                else {
                    // Send 'data' config to the device
                    sendConfig(worker, cmd, data);
                }
            }

            // Create context save list
            if (isAdminContext()) {
                modifiedContextList = new ArrayList<>();
                String[] lines = data.split("\n");
                for (int n = 0; n < lines.length; n++) {
                    String trimmed = lines[n].trim();
                    if ((trimmed = getMatch(trimmed, "^(?:changeto )?context (\\S+)$")) != null) {
                        modifiedContextList.add(trimmed);
                    }
                }
            }

            // Done
            nedReportProgress(worker, "sending config ok", start);

        } catch (Exception e) {
            nedReportProgress(worker, "sending config error", start);
            throw e;

        } finally {
            maapiDetach(worker, fromTh, toTh);
        }
    }


    /**
     *
     * @param
     * @return
     * @throws Exception
     */
    private String modifyOutput(NedWorker worker, String data, int fromTh, int toTh, boolean dry)
        throws NedException, IOException, SSHSessionException, ApplyException {

        logInfo(worker, "BEGIN out-transforming");

        final long start = nedReportProgress(worker, "modifying output...", 0);
        try {
            // Reset timeout to NED standard
            lastTimeout = setReadTimeout(worker);

            //
            // Scan meta-data and modify data
            //
            traceDebug2(worker, "begin out-transforming - meta-data");
            long perf = tick(0);
            data = metaData.modifyData(worker, data, maapi, fromTh);
            traceDebug2(worker, "done out-transforming - meta-data "+tickToString(perf));

            //
            // Modify ACL - inject line numbers and log disable
            //
            traceInfo(worker, "begin out-transforming - access-list");
            perf = tick(0);
            data = modifyACL(worker, data, fromTh, toTh, dry);
            traceInfo(worker, "done out-transforming - access-list "+tickToString(perf));

            //
            // Reorder data
            //
            traceDebug2(worker, "begin out-transforming - reorder");
            perf = tick(0);
            data = reorderOutput(worker, data);
            traceDebug2(worker, "done out-transforming - reorder "+tickToString(perf));

            //
            // LINE-BY-LINE - applyConfig
            //
            traceDebug2(worker, "begin out-transforming - line-by-line");
            perf = tick(0);
            StringBuilder sb = new StringBuilder();
            String context = "";
            String toptag = "";
            int topModeOffset = 0;
            String[] lines = data.split("\n");
            String match;
            for (int n = 0; n < lines.length; n++) {
                String trimmed = lines[n].trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                // Ignore meta-data
                if (trimmed.startsWith(META_DATA)) {
                    sb.append(lines[n]+"\n");
                    continue;
                }

                // Update context, toptag and topModeOffset
                if (lines[n].startsWith("changeto context ")
                    && (context = getMatch(lines[n], "changeto context (\\S+)")) != null) {
                    toptag = "";
                    topModeOffset = 1;
                } else if (trimmed.equals("changeto system")) {
                    context = "";
                    toptag = "";
                    topModeOffset = 0;
                } else if (Character.isLetter(lines[n].charAt(topModeOffset))) {
                    toptag = trimmed;
                }

                String cmd = trimmed.startsWith("no ") ? trimmed.substring(3) : trimmed;
                String nextline = (n + 1 < lines.length) ? lines[n+1] : "";

                //
                // context *
                //
                if (cmd.startsWith("context ")
                    && isAdminContext()
                    && (match = getMatch(trimmed, "context (\\S+)")) != null
                    && isSupportedContext(match) == -1) {
                    // Check context/list if may access context
                    traceInfo(worker, "context '"+match+"' not in context/list");
                    throw new ApplyException("context '"+match+"' not in context/list",true,false);
                }

                //
                // no anyconnect-custom-data * * <value(s)>
                //
                if (trimmed.startsWith("no anyconnect-custom-data ")
                    && (match = getMatch(trimmed, "(no anyconnect-custom-data \\S+ \\S+) .*")) != null) {
                    traceVerbose(worker, "transformed => '"+trimmed+"' to '"+match+"'");
                    sb.append(match+"\n");
                    while (n+1 < lines.length && lines[n+1].trim().startsWith(match)) {
                        // Strip additional delete lines of same leaf-list
                        traceVerbose(worker, "transformed => stripped '"+lines[++n].trim()+"'");
                    }
                    continue;
                }

                //
                // NETSIM
                //
                if (isNetsim()) {
                    if (trimmed.equals("changeto system")) {
                        continue;
                    }
                    sb.append(lines[n]+"\n");
                    continue;
                }


                //
                // READ DEVICE BELOW
                //
                String output = null;

                //
                // pager - redisable pager in system mode
                //
                if (trimmed.startsWith("pager lines ")) {
                    traceVerbose(worker, "transformed => redisabled terminal pager");
                    sb.append(lines[n]+"\n");
                    sb.append("terminal pager 0\n");
                    continue;
                }

                //
                // changeto context * / no interface *
                //
                if (!context.isEmpty() && toptag.equals(trimmed) && lines[n].startsWith(" no interface ")) {
                    output = " !suppressed:"+lines[n];
                }

                //
                // username *
                //
                else if (cmd.startsWith("username ")
                         && (match = getMatch(trimmed, "username\\s+\\\"(\\S+)\\\" ")) != null) {
                    output = lines[n].replace("\""+match+"\"", match);
                }

                //
                // tcp-map / tcp-options range
                //
                else if (toptag.startsWith("tcp-map ") && trimmed.contains("tcp-options range ")) {
                    output = lines[n].replaceAll("range (\\d+)", "range $1 $1");
                }

                //
                // banner exec|login|motd
                // group-policy * attributes / banner value
                //
                else if (trimmed.startsWith("banner ")) {
                    Pattern p = Pattern.compile("banner (\\S+)[ ]+(.*)");
                    Matcher m = p.matcher(trimmed);
                    if (m.find()) {
                        String name = m.group(1);
                        String message = stringDequote(m.group(2));
                        message = message.replaceAll("\r\n", "\nbanner "+name+" ");
                        String banner = name.contains("value") ? "no banner\n" : "no banner "+name+"\n";
                        banner += "banner "+name+" "+message;
                        traceVerbose(worker, "transformed => dequoted banner "+name);
                        lines[n] = banner;
                    }
                }

                //
                // crypto ca certificate chain * / certificate *
                //
                else if (toptag.startsWith("crypto ca certificate chain ")
                         && lines[n].startsWith(" certificate ")
                         && nextline.trim().startsWith("\"")) {
                    // Add certificate line and dequote certificate
                    traceVerbose(worker, "output => dequoted '"+trimmed+"'");
                    sb.append(lines[n++]+"\n");
                    lines[n] = stringDequote(lines[n].trim()); // note: prompt shows after each line
                }

                //
                // [ipv6 ]icmp <nameif> <permit|deny rule> -> [ipv6 ]icmp <permit|deny rule> <nameif>
                //
                else if (getMatch(lines[n], "(?:no )?(?:ipv6 )?icmp (\\S+) (?:permit|deny) ") != null) {
                    output = lines[n].replaceAll("icmp (\\S+) (.+)", "icmp $2 $1");
                }

                // no monitor-interface
                else if (lines[n].contains("no monitor-interface ")
                         && !lines[n].contains("service-module")) {
                    output = lines[n].replaceAll("no monitor-interface (\\S+) disable",
                                                 "monitor-interface $1");
                    output = output.replaceAll("no monitor-interface (\\S+) enable",
                                               "no monitor-interface $1");
                }

                // monitor-interface
                else if (trimmed.startsWith("monitor-interface ")
                         && !lines[n].contains("service-module")) {
                    output = lines[n].replaceAll("monitor-interface (\\S+) disable",
                                                 "no monitor-interface $1");
                    output = output.replace(" enable", "");
                }

                // as-path access-list *
                else if (cmd.startsWith("as-path access-list ")
                         && ((match = getMatch(trimmed, "as-path access-list \\d+ (.+)")) != null)) {
                    output = lines[n].replace(match, stringDequote(match));
                }

                // no disable passive-interface
                else if (trimmed.startsWith("no disable passive-interface ")) {
                    output = lines[n].replace("no disable passive-interface ",
                                              "passive-interface ");
                }

                // disable passive-interface
                else if (trimmed.startsWith("disable passive-interface ")) {
                    output = lines[n].replace("disable passive-interface ",
                                              "no passive-interface ");
                }

                // no no-list (generic trick for no-lists)
                else if (lines[n].matches("^\\s*no .* no-list .*$")
                         && !lines[n].contains(" service-module")) {
                    output = lines[n].replaceAll("no (.*) no-list (.*)", "$1 $2");
                }

                // no-list (generic trick for no-lists)
                else if (lines[n].contains("no-list ")
                         && !lines[n].contains(" service-module")) {
                    output = "no " + lines[n].replace("no-list ", "");
                }

                // noconfirm delete's:
                else if (trimmed.startsWith("no crypto ca trustpoint")
                         || lines[n].matches("^\\s*no context (\\S+)\\s*$")) {
                    output = lines[n] + " noconfirm";
                }

                // Ignore delete of certificates
                else if (trimmed.startsWith("no crypto ca certificate chain ")) {
                    output = "!ignored on device: " + lines[n];
                }

                // Ignore delete of changeto context
                else if (lines[n].startsWith("no changeto context ")) {
                    output = "!ignored on device: " + lines[n];
                }

                // fragment, reorder
                else if (trimmed.matches("^(?:no )?fragment \\S+ \\S+ .*$")) {
                    output = lines[n].replaceFirst("fragment (\\S+) (\\S+) (\\S+)",
                                                   "fragment $2 $3 $1");
                }

                //
                // Transform lines[n] -> XXX
                //
                if (output != null && !output.equals(lines[n])) {
                    if (output.isEmpty()) {
                        traceVerbose(worker, "transformed => stripped '"+trimmed+"'");
                    } else {
                        traceVerbose(worker, "transformed => '"+trimmed+"' to '"+output.trim()+"'");
                        sb.append(output+"\n");
                    }
                }

                // Unmodified - append to buffer
                else if (lines[n] != null && !lines[n].isEmpty()) {
                    sb.append(lines[n]+"\n");
                }
            }
            data = "\n" + sb.toString();
            traceDebug2(worker, "done out-transforming - line-by-line "+tickToString(perf));

            // Done
            logInfo(worker, "DONE out-transforming "+tickToString(start));
            nedReportProgress(worker, "modifying output ok", start);
            return data;

        } catch (Exception e) {
            nedReportProgress(worker, "modifying output error", start);
            throw e;
        }
    }


    // Config which refers to access-list:
    //   access-group ? global
    //   class-map * / match access-list
    //   crypto map * match address
    //   group-policy * attributes / split-tunnel-network-list
    //   group-policy * attributes / vpn-filter value
    //   router * / distribute-list
    //   route-map * / match ip address
    //   wccp * redirect-list
    // Config which refers to object:
    //   nat *
    //   snmp-server host-group *
    private StringBuilder[] sessionModifyData(NedWorker worker, String data, int toTh)
        throws NedException {

        // Get forward-reference enable
        // FIXME: context support
        String p = this.confRoot+"forward-reference/enable";
        boolean forwardReferenceEnabled = false;
        try {
            if (maapi.exists(toTh, p)) {
                forwardReferenceEnabled = true;
            }
        } catch (Exception e) {
            throw new NedException("Failed to read forward-reference enable from CDB");
        }
        traceInfo(worker, "SESSION: forward-reference enable = "+forwardReferenceEnabled);

        StringBuilder[] sb = new StringBuilder[4];
        for (int n = 0; n < 4; n++) {
            sb[n] = new StringBuilder();
        }

        int index = 0;
        String[] lines = data.split("\n");
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // [3] - time-range is used in access-lists, delete last
            if (trimmed.startsWith("no time-range ")) {
                sb[3].append(line+"\n");
                continue;
            }

            //
            // [1] - session object
            // [2] - session access-list, no xxx
            //
            if (isSessionCommand(lines, n)) {
                if (trimmed.startsWith("no ") || trimmed.startsWith("clear config access-list ")) {
                    // Keep index
                } else {
                    // After first session create command, put non-session config last
                    index = 3;
                }
                if (trimmed.startsWith("object ") || trimmed.startsWith("object-group ")) {
                    String obj = "";
                    boolean invalid = false;
                    for (; n < lines.length; n++) {
                        obj += (lines[n] + "\n");
                        trimmed = lines[n].trim();
                        if (trimmed.startsWith("nat ")) {
                            invalid = true; // nat can't be configured in session(!)
                        }
                        if (trimmed.equals("!") || trimmed.equals("exit")) {
                            break;
                        }
                    }
                    if (obj.trim().startsWith("object network ") && invalid) {
                        sb[3].append(obj);
                    } else if (forwardReferenceEnabled) {
                        sb[2].append(obj);
                    } else {
                        sb[1].append(obj);
                    }
                } else {
                    sb[2].append(line+"\n");
                }
                continue;
            }

            //
            // [0] - first
            // [3] - last
            //
            sb[index].append(line+"\n");
        }

        for (int n = 0; n < 4; n++) {
            if (sb[n].length() > 0) {
                traceVerbose(worker, "APPLY_SESSION-"+n+":\n"+sb[n].toString());
            }
        }

        return sb;
    }


    /**
     * Check if command can be configured in a session
     * @param
     * @return True if session command, else false
     */
    private boolean isSessionCommand(String[] lines, int n) {
        String trimmed = lines[n].trim();
        String command = trimmed.startsWith("no ") ? trimmed.substring(3) : trimmed;
        String nextline = (n + 1 < lines.length) ? lines[n+1] : "";
        if (command.startsWith("object ") || command.startsWith("object-group ")) {
            return true;
        }
        if (command.startsWith("clear config access-list ")) {
            return true;
        }
        if (aclGetName(lines[n], nextline, null) != null) {
            if (trimmed.startsWith("! insert") || trimmed.startsWith("! move")) {
                trimmed = nextline.trim();
            }
            if (getMatch(trimmed, "access-list (\\S+) webtype ") != null) {
                return false;
            }
            return true;
        }
        return false;
    }


    /**
     * Modify access-list diffset before sending to device in order to support insert|move
     * @param
     * @return
     * @throws NedException
     */
    private String modifyACL(NedWorker worker, String data, int fromTh, int toTh, boolean dry) throws NedException {
        String[] lines = data.split("\n");
        StringBuilder sb = new StringBuilder();
        String context = null;
        HashMap<String, LinkedList<String>> aclListCache = new HashMap<>();
        int next;
        for (int n = 0; n < lines.length; n = next) {
            String line = lines[n];
            String nextline = (n + 1 < lines.length) ? lines[n+1] : "";

            // Cache context
            if (line.startsWith("changeto context ")) {
                context = getMatch(line, "changeto context[ ]+(\\S+)");
            } else if (context != null && line.equals("!")) {
                context = null;
            }

            // Not an access-list line, add and continue
            String name = aclGetName(line, nextline, null);
            if (name == null) {
                sb.append(line+"\n");
                next = n + 1;
                continue;
            }
            String fullname = name;
            if (context != null) {
                fullname = "context{"+context+"}/"+name;
            }

            // Update timeout
            lastTimeout = resetReadTimeout(worker, lastTimeout);

            //
            // Found access-list entry modification(s), find next
            //
            boolean haveDelete = false;
            boolean haveCreate = false;
            boolean haveInsert = false;
            int logFirstTen = 10;
            int offset;
            String lastrule = "";
            for (next = n; next < lines.length; next += offset) {
                offset = 1;
                String trimmed = lines[next].trim();

                // insert or move annotation
                if (trimmed.startsWith("! insert") || trimmed.startsWith("! move")) {

                    // Strip NSO bug adding unnecessary "! insert after" when creating new lists
                    if (!haveInsert && !haveDelete && trimmed.startsWith("! insert after")) {
                        //   access-list ACL "remark line 1"
                        //   ! insert after "remark line 1"
                        int last = lastrule.indexOf('"');
                        int curr = trimmed.indexOf('"');
                        if (last > 0 && curr > 0
                            && lastrule.substring(last).equals(trimmed.substring(curr))) {
                            if (logFirstTen-- > 0) {
                                logInfo(worker, "ACL: trimmed "+fullname+" comment: '"+trimmed+"' [NSO PATCH]");
                            } else {
                                traceVerbose(worker, "ACL: trimmed "+fullname+" comment: '"+trimmed+"' [NSO PATCH]");
                            }
                            lines[next] = "";
                            offset--;
                        }
                    }
                    trimmed = lines[next+1].trim();
                    offset++;
                }

                // create or insert
                if (trimmed.startsWith("access-list "+name+" ")) {
                    if (offset == 2) {
                        haveInsert = true;
                    } else {
                        haveCreate = true;
                    }
                } else if (trimmed.equals("access-list "+name)) {
                    haveCreate = true;
                    if (!dry) {
                        throw new NedException("ACL: incomplete command: '"+trimmed+"'");
                    }
                }

                // delete
                else if (trimmed.startsWith("no access-list "+name+" ")) {
                    haveDelete = true;
                } else if (trimmed.equals("no access-list "+name)) {
                    haveDelete = true;
                    if (!dry) {
                        throw new NedException("ACL: incomplete command: '"+trimmed+"'");
                    }
                }

                // end of loop, check if rule changes are split by dependencies
                else {
                    for (int scan = next + offset; scan < lines.length; scan++) {
                        if (aclNextIsSame(name, lines, scan)) {
                            // modification of acl split by dependencies -> use insert code
                            traceInfo(worker, "ACL: split "+fullname+" modifications");
                            haveInsert = true;
                            break;
                        }
                    }
                    break;
                }
                lastrule = trimmed;
            }

            traceInfo(worker, "ACL: name="+fullname+" n="+n+" next="+next
                      +" create="+haveCreate+" delete="+haveDelete+" insert="+haveInsert);
            if (n == next) {
                throw new NedException("ACL: internal deadlock error, last command: '"+lines[n]+"'");
            }

            // Update timeout
            lastTimeout = resetReadTimeout(worker, lastTimeout);

            // Look up list in cache, in case we got dependency split modifications
            LinkedList<String> aclList = aclListCache.get(fullname);
            if (aclList != null) {
                traceInfo(worker, "  Using cached access-list "+fullname);
            }

            //
            // ned-settings cisco-asa write recreate-acl-threshold
            //
            int reSize = 0;
            if (haveDelete && recreateAclThreshold > 0 && isDevice()) {
                try {
                    reSize = maapi.getNumberOfInstances(toTh, aclPath(context, name)+"/rule");
                    if (reSize > 0 && reSize <= recreateAclThreshold && reSize < (next-n-1)) {
                        traceInfo(worker, "ACL: to-size = "+reSize+" (triggering recreate)");
                    } else {
                        traceInfo(worker, "ACL: to-size = "+reSize+" (recreate ignored)");
                        reSize = 0;
                    }
                } catch (Exception e) {
                    logError(worker, "ACL getNumberOfInstances("+fullname+") ERROR:", e);
                }
            }

            //
            // Non-insert consecutive add or delete
            //
            if (!haveInsert && aclList == null && reSize == 0) {

                // ned-setting cisco-asa write compress-acl-delete
                if (compressAclDelete
                    && !haveCreate
                    && !maapiExists(worker, toTh, aclPath(context, name))) {
                    traceInfo(worker, "  compress-deleting access-list "+fullname);
                    if (isNetsim()) {
                        sb.append("no access-list "+name+"\n");
                    } else {
                        sb.append("clear config access-list "+name+"\n");
                    }
                }

                // Apply all changes line-by-line (add first to not temporarily delete list)
                else {
                    traceVerbose(worker, "  modifying access-list "+fullname);

                    // Trim identical delete before create (acl option(s) change)
                    if (haveCreate && haveDelete) {
                        for (int i = n; i < next - 1; i++) {
                            String trimmed = lines[i].trim();
                            if (!trimmed.startsWith("no access-list ")) {
                                continue;
                            }
                            String nexttrim = lines[i+1].trim();
                            if (aclKey(trimmed.substring(3)).equals(aclKey(nexttrim))) {
                                traceVerbose(worker, "    trimmed '"+trimmed+"'");
                                lines[i] = "";
                            }
                        }
                    }

                    // Add rule(s) before deleting in order to not delete locked acl
                    if (haveCreate) {
                        for (int i = n; i < next; i++) {
                            String trimmed = lines[i].trim();
                            if (!trimmed.startsWith("access-list ")) {
                                continue;
                            }
                            traceVerbose(worker, "    adding '"+trimmed+"'");
                            aclCmdAdd(lines[i], sb);
                        }
                    }

                    // Delete rule(s)
                    if (haveDelete) {
                        for (int i = n; i < next; i++) {
                            String trimmed = lines[i].trim();
                            if (!trimmed.startsWith("no access-list ")) {
                                continue;
                            }
                            traceVerbose(worker, "   deleting '"+trimmed+"'");
                            aclCmdAdd(lines[i], sb);
                        }
                    }
                }
                continue;
            }


            //
            // haveInsert - due to !insert|!move or dependency-split modifications
            //

            //
            // NETSIM or recreate-acl-threshold ned-setting - Recreate access-list
            //
            if (isNetsim() || reSize > 0) {
                // Delete access-list then add back to support insertions
                if (maapiExists(worker, fromTh, aclPath(context, name))) {
                    if (isNetsim()) {
                        sb.append("no access-list "+name+"\n");
                    } else {
                        sb.append("clear config access-list "+name+"\n");
                    }
                }

                // Get current (to-transaction) access-list and re-apply
                aclList = aclListGet(worker, context, name, fullname, toTh);
                if (aclList != null) {
                    traceVerbose(worker, "  recreating access-list "+fullname+", new size = "+(aclList.size()-1));
                    aclListToTrace(worker, aclList, "to");
                    Iterator it = aclList.iterator();
                    for (int i = 0; it.hasNext(); i++) {
                        String value = (String)it.next();
                        if (i == 0) {
                            continue;
                        }
                        String acl = "access-list "+name+" "+value;
                        if (isDevice()) {
                            aclCmdAdd(acl, sb);
                        } else {
                            sb.append(acl+"\n");
                        }
                    }
                } else {
                    traceVerbose(worker, "  deleting access-list "+fullname);
                }
                continue;
            }

            // Read current (from-transaction) list from CDB [NOTICE: slow]
            if (aclList == null) {
                aclList = aclListGet(worker, context, name, fullname, fromTh);
            }

            //
            // New access-list
            //
            if (aclList == null || aclList.size() == 1) {
                traceVerbose(worker, "  creating access-list "+fullname);
                for (; n < next; n++) {
                    String trimmed = lines[n].trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    if (trimmed.startsWith("! insert") || trimmed.startsWith("! move")) {
                        continue;  // Trim, not needed with a new list
                    }
                    traceVerbose(worker, "    adding '"+trimmed+"'");
                    aclCmdAdd(lines[n], sb);
                }
                continue;
            }

            // Reset timeout
            lastTimeout = setReadTimeout(worker);

            //
            // REAL DEVICE - apply insertion/reorder rule changes
            //
            long perf = tick(0);
            int fromsize = aclList.size() - 1;
            traceVerbose(worker, "  modifying "+fullname+" [size "+fromsize+"] with "+(next-n)+" command(s)");
            aclListToTrace(worker, aclList, "from");
            int index = -1;
            LinkedList<String> cmdList = new LinkedList<>();
            for (; n < next; n++) {
                line = lines[n];
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                nextline = (n + 1 < lines.length) ? lines[n+1] : "";

                // Update timeout
                lastTimeout = resetReadTimeout(worker, lastTimeout);

                // ! insert after|before <line>
                // ! move after|before <line>
                if (trimmed.startsWith("! ")) {
                    n = n + 1;
                    String[] group = getMatches(line, "! (move|insert) (after|before) (.*)");
                    if (Integer.parseInt(group[0]) != 3) {
                        throw new NedException("ACL: malformed obu: "+nextline);
                    }
                    String rule = nextline.trim().replace("access-list "+name+" ", "");

                    // If moving a rule, first remove it from ACL cache and generate no-command
                    if (group[1].equals("move")) {
                        traceVerbose(worker, "    moving '"+nextline+"' "+group[2]+" '"+group[3]+"'");
                        index = aclIndexOf(worker, aclList, rule, false);
                        if (index == -1) {
                            throw new NedException("ACL: finding rule to move: '"+nextline+"'");
                        }
                        aclList.remove(rule);
                        aclCmdAdd("no "+nextline, cmdList);
                    } else {
                        traceVerbose(worker, "    inserting '"+nextline+"' "+group[2]+" '"+group[3]+"'");
                    }

                    // WORKAROUND for NSO sending 'insert before' on the following (yet non-existing) rule
                    if (aclList.size() == 1) {
                        traceInfo(worker,"ACL: WARNING superfluous obu '"+line+"', adding '"+rule+"' last [NSO PATCH]");
                        aclList.add(rule);
                        aclCmdAdd(nextline, cmdList);
                        index = -1; // Reset index to add next entry last
                        continue;
                    }

                    // Now look up where to insert it
                    index = aclIndexOf(worker, aclList, group[3], false);
                    if (index == -1) {
                        throw new NedException("ACL: finding rule for: '"+line+"'");
                    }
                    if (group[2].contains("after")) {
                        index++;
                    }
                    // Update the internal ACL cache and add device command
                    aclList.add(index, rule);
                    aclCmdAdd("access-list "+name+" line "+index+" "+rule, cmdList);
                    index = -1; // Reset index to add next entry last
                }

                // no access-list <name> <rule>
                else if (trimmed.startsWith("no access-list ")) {
                    String rule = trimmed.replace("no access-list "+name+" ", "");
                    index = aclIndexOf(worker, aclList, rule, false);
                    if (index == -1) {
                        throw new NedException("ACL: finding rule '"+rule+"' to delete");
                    }
                    traceVerbose(worker, "    deleting '"+line+"'");
                    aclList.remove(rule);
                    if (line.contains("remark ") && line.contains("]]")) {
                        aclCmdAdd("no access-list "+name+" line "+index+" "+rule, cmdList);
                    } else {
                        aclCmdAdd(line, cmdList);
                    }
                    index = -1; // Only nextline is inserted/moved, next one added last
                }

                // access-list <name> <rule>
                else {
                    String rule = trimmed.replace("access-list "+name+" ", "");
                    int current = aclIndexOf(worker, aclList, rule, true);
                    if (current != -1 && aclEquals(aclList, current, rule)) {
                        traceVerbose(worker, "    ignoring duplicate '"+line+"' (index="+index+")");
                        index = current + 1;
                        continue;
                    } else if (current != -1) {
                        traceVerbose(worker, "    updating '"+line+"'");
                        index = current + 1;
                        aclList.set(current, rule);
                        aclCmdAdd(line, cmdList);
                    } else if (index != -1) {
                        traceVerbose(worker, "    adding '"+line+"' at index="+index);
                        aclList.add(index, rule);
                        aclCmdAdd("access-list "+name+" line "+index+" "+rule, cmdList);
                        index++;
                    } else {
                        traceVerbose(worker, "    adding '"+line+"'");
                        aclList.add(rule);
                        aclCmdAdd(line, cmdList);
                    }
                }
            }
            aclListToTrace(worker, aclList, "to");
            traceDebug2(worker, "  modified access-list "+fullname+" "+tickToString(perf));

            //
            // Assert that list is not unnecessarily temporarily deleted
            //
            perf = tick(0);
            int size = fromsize;
            for (int c = 0; c < cmdList.size() - 1; c++) {
                line = cmdList.get(c);
                nextline = cmdList.get(c+1);
                size += (line.startsWith("access-list ") ? 1 : -1);
                if (size > 0) {
                    continue;
                }

                // This line must be a delete and the next must be an add
                if (nextline.startsWith("no ")) {
                    throw new NedException("ACL: malformed line: '"+nextline+"'");
                }

                // Trim identical rule delete before create to avoid deleting locked acl
                if (aclKey(line.substring(3)).equals(aclKey(nextline))) {
                    traceInfo(worker, "ACL: stripping '"+line+"'");
                    cmdList.remove(c);
                    continue;
                }

                // Put the create before the delete to avoid deleting locked acl
                traceInfo(worker, "ACL: swapping '"+line+"' and '"+nextline+"'");
                size++;
                cmdList.set(c++, nextline);
                cmdList.set(c, line);
            }
            traceDebug2(worker, "  asserted access-list "+fullname+" "+tickToString(perf));

            // Add access-list lines to string buffer for output to device
            perf = tick(0);
            for (int c = 0; c < cmdList.size(); c++) {
                sb.append(cmdList.get(c)+"\n");
            }
            traceDebug2(worker, "  added access-list "+fullname+" command(s) "+tickToString(perf));

            // Cache aclList if modifications are split by dependencies
            if (aclList != null) {
                perf = tick(0);
                aclListCache.put(fullname, aclList);
                traceDebug2(worker, "  cached access-list "+fullname+" "+tickToString(perf));
            }

            // Done
        }
        data = sb.toString();

        // TODO: WARN for duplicate access-list lines on device because not supported
        return data;
    }


    /**
     * Trace entire access-list to debug3 level
     * @param
     */
    private void aclListToTrace(NedWorker worker, LinkedList<String> aclList, String pfx) {
        if (devTraceLevel < TRACE_DEBUG3) {
            return;
        }
        String name = "";
        Iterator it = aclList.iterator();
        for (int i = 0; it.hasNext(); i++) {
            String value = (String)it.next();
            if (i == 0) {
                name = value;
                traceDebug3(worker, "   +++ begin ACL["+pfx+"] "+name);
            } else {
                traceDebug3(worker, "   ["+i+"] = "+value);
            }
        }
        traceDebug3(worker, "   +++ end ACL["+pfx+"] "+name);
    }


    /**
     * Return the key part of an access-list rule
     * @param
     * @return
     */
    private String aclKey(String line) {
        int lastQuote = line.lastIndexOf('"');
        if (lastQuote < 0) {
            return line;
        }
        return line.substring(0, lastQuote);
    }


    /**
     * Find index of access-list rule, searching backwards if adding
     * @param
     * @return
     */
    private int aclIndexOf(NedWorker worker, LinkedList<String> aclList, String line, boolean add) {
        long perf = tick(0);
        String key = aclKey(line);
        int index = -1;
        if (add) {
            Iterator it = aclList.descendingIterator();
            for (int i = aclList.size() - 1; it.hasNext(); i--) {
                String keyx = aclKey((String)it.next());
                if (key.equals(keyx)) {
                    index = i;
                    break;
                }
            }
        } else {
            Iterator it = aclList.iterator();
            for (int i = 0; it.hasNext(); i++) {
                String keyx = aclKey((String)it.next());
                if (key.equals(keyx)) {
                    index = i;
                    break;
                }
            }
        }
        traceDebug2(worker, "aclIndexOf("+line+") index = "+index+" "+tickToString(perf));
        return index;
    }


    /**
     * Check if list is already in list
     * @param
     * @return
     */
    private boolean aclEquals(LinkedList<String> aclList, int index, String line) {
        String linex = aclList.get(index);
        return linex.equals(line);
    }


    /**
     * Get path to access-list entry for lookup in  CDB
     * @param
     * @return
     */
    private String aclPath(String context, String name) {
        String path = confRoot+"access-list/access-list-id{"+name+"}";
        if (context != null) {
            path = path.replace("asa:", "asa:changeto/context{"+context+"}/");
        }
        return path;
    }


    /**
     * Read access-list from CDB using Maapi.getObjects()
     * @param
     * @return
     * @throws NedException
     */
    private LinkedList<String> aclListGet(NedWorker worker, String context, String name, String fullname, int th)
        throws NedException {
        long start = tick(0);
        try {
            if (writeSerMaapiAclRead) {
                traceVerbose(worker, "Waiting for Maapi acl-read semaphore");
                final long wait = tick(0);
                resetTimeout(worker, Integer.MAX_VALUE, 0);
                maapiSemaphore.acquire();
                traceVerbose(worker, "Acquired Maapi acl-read semaphore "+tickToString(wait));
                lastTimeout = resetReadTimeout(worker, lastTimeout);
                start = lastTimeout;
            }

            // Verify list exists
            String path = aclPath(context, name);
            if (!maapiExists(worker, th, path)) {
                traceVerbose(worker, "  access-list "+fullname+" not found");
                return null;
            }

            // Read number of instances
            int num = maapi.getNumberOfInstances(th, path + "/rule");
            traceDebug2(worker, "  access-list "+fullname+" getNumberOfInstances() = "+num);
            if (num <= 0) {
                traceInfo(worker, "  WARNING access-list "+fullname+" is empty");
                return null;
            }

            // Add access-list <name> header
            LinkedList<String> aclList = new LinkedList<>();
            aclList.add("access-list "+name+" ");

            // Bulk-read all rules
            final int numLeaves = 6;
            MaapiCursor cr = maapi.newCursor(th, path + "/rule");
            List<ConfObject[]> list = maapi.getObjects(cr, numLeaves, num);
            traceDebug2(worker, "  access-list "+fullname+" getObjects() = "+list.size());

            // Add all the rules
            for (int n = 0; n < list.size(); n++) {
                ConfObject[] objs = list.get(n);
                StringBuilder sb = new StringBuilder("\"" + objs[0].toString().trim() + "\"");
                if (!"J_NOEXISTS".equals(objs[1].toString())) {
                    sb.append(" log");
                    if (!"J_NOEXISTS".equals(objs[2].toString())) {
                        sb.append(" "+objs[2].toString().trim());
                    }
                    if (!"J_NOEXISTS".equals(objs[3].toString())) {
                        sb.append(" interval "+objs[3].toString().trim());
                    }
                }
                if (!"J_NOEXISTS".equals(objs[4].toString())) {
                    sb.append(" time-range "+objs[4].toString().trim());
                }
                if (!"J_NOEXISTS".equals(objs[5].toString())) {
                    sb.append(" inactive");
                }
                aclList.add(sb.toString());

                // Update timeout each 100 rules
                if (n % 100 == 0) {
                    lastTimeout = resetReadTimeout(worker, lastTimeout);
                }
            }

            traceInfo(worker, "  Read access-list "+fullname+" ("+num+" rules) from CDB "+tickToString(start));
            return aclList;

        } catch (Exception e) {
            throw new NedException("aclListGet : "+e.getMessage(), e);

        } finally {
            if (writeSerMaapiAclRead) {
                traceVerbose(worker, "Released Maapi acl-read semaphore "+tickToString(start));
                maapiSemaphore.release();
            }
        }
    }


    /**
     * Determine if config line[nextline] is an access-list modification
     * @param
     * @return
     */
    private String aclGetName(String line, String nextline, String name) {

        line = line.trim();
        if (line.startsWith("! insert") || line.startsWith("! move")) {
            line = nextline.trim();
        }

        if (name == null) {
            // Strip command before access-list name
            if (line.startsWith("access-list ")) {
                name = line.substring(12);
            } else if (line.startsWith("no access-list ")) {
                name = line.substring(15);
            } else {
                return null;
            }

            // Strip access-list rule to get name only
            int index = name.indexOf(' ');
            if (index > 0) {
                name = name.substring(0, index);
            }

            if ("deny-flow-max".equals(name) || "alert-interval".equals(name)) {
                return null;
            }
            return name;

        } else if (line.startsWith("access-list "+name+" ")) {
            return name;
        } else if (line.startsWith("no access-list "+name+" ")) {
            return name;
        }
        return null;
    }


    /**
     *
     * @param
     * @return
     */
    private boolean aclNextIsSame(String name, String[] lines, int n) {
        if (n + 1 >= lines.length) {
            return false;
        }
        String line = lines[n+1];
        String nextline = (n + 2 < lines.length) ? lines[n+2] : "";
        if (aclGetName(line, nextline, name) == null) {
            return false;
        }
        return true;
    }


    /**
     *
     * @param
     * @return
     */
    private String aclCmdTransform(String line) {
        line = line.replace(" informational", "");
        line = line.replace(" interval 300", "");
        if (isDevice()) {
            if (!line.trim().startsWith("no ")
                && line.contains("\"extended ")
                && !line.contains(" time-range ")
                && !line.contains(" log")) {
                if (line.endsWith(" inactive")) {
                    line = line.replace(" inactive", " log disable inactive");
                } else {
                    line += " log disable";
                }
            }
            line = line.replace("\"", "");
            if (getMatch(line, "line (\\d+) standard") != null) {
                line = line.replaceFirst(" standard", "");
            }
            if (line.contains(" remark ") && line.endsWith("]]")) {
                // Strip injected duplicate remark index
                int i = line.lastIndexOf("[[");
                if (i > 0) {
                    line = line.substring(0, i);
                }
            }
        }
        return line;
    }


    /**
     *
     * @param
     */
    private void aclCmdAdd(String line, StringBuilder sb) {
        line = aclCmdTransform(line);
        sb.append(line+"\n");
    }


    /**
     *
     * @param
     */
    private void aclCmdAdd(String line, LinkedList<String> cmdList) {
        line = aclCmdTransform(line);
        cmdList.add(line);
    }


    /**
     * Reorder output data
     * @param
     * @return
     */
    private String reorderOutput(NedWorker worker, String data) {

        //
        // Pass 1 - assert non-empty mode-list by swapping last delete with first create
        //
        String[] lines = data.split("\n");
        for (int n = 0; n < lines.length; n++) {
            String trimmed = lines[n].trim();
            if (trimmed.startsWith("object-group ")) {
                for (n = n + 1; n < lines.length - 1; n++) {
                    trimmed = lines[n].trim();
                    if (trimmed.equals("!") || trimmed.equals("exit")) {
                        break;
                    }
                    if (!trimmed.startsWith("no ")) {
                        break;
                    }
                    String nextline = lines[n+1].trim();
                    if (nextline.equals("!") || nextline.equals("exit")) {
                        continue;
                    }
                    if (nextline.startsWith("no ")) {
                        continue;
                    }
                    traceInfo(worker, "transformed => swapped '"+trimmed+"' and '"+nextline+"'");
                    String templine = lines[n];
                    lines[n] = lines[n+1];
                    lines[n+1] = templine;
                    break;
                }
            }
        }

        //
        // Pass 2 - reorder top mode config
        //
        StringBuilder middle = new StringBuilder();
        StringBuilder first = new StringBuilder();
        StringBuilder last = new StringBuilder();
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];

            // Reverse order of failover group deletes
            if (line.startsWith("no failover group ")) {
                traceVerbose(worker, "DIFFPATCH => moved '"+line+"' last (reversed)");
                last.insert(0, line+"\n");
            }

            // Default case
            else {
                middle.append(line+"\n");
            }
        }
        data = "\n" + first.toString() + middle.toString() + last.toString();

        return data;
    }


    /**
     * Send/upload config using SCP
     * @param
     * @throws Exception
     */
    private void scpPutConfig(NedWorker worker, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {
        final long start = tick(0);
        logInfo(worker, "BEGIN SCP upload "+data.length()+" bytes");

        // Strip meta-data tags and decrypt MAAPI secrets
        String meta = "";
        String[] lines = data.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            String trimmed = line.trim();
            if (trimmed.startsWith(META_DATA)) {
                meta += (trimmed + "\n");
                continue;
            }
            if (meta.contains(" :: secret") || meta.contains(" :: support-encrypted-password")) {
                String decrypted = decryptPassword(worker, line);
                sb.append(decrypted+"\n");
            } else {
                sb.append(line+"\n");
            }
            meta = "";
        }
        data = sb.toString();

        String dir = scpDirectory();
        String file = scpFile(worker, true);
        try {
            // Make sure we accidentally do not have a file with same name
            print_line_exec(worker, "delete /noconfirm "+file);

            // Open SCP connection and upload the config to a temporary file
            String fallbackAddress = scpOpenSession(worker, null, "upload");
            try {
                if ("scpupload".equals(simFail)) {
                    throw new SSHSessionException(0, "simulated read timeout before SCP upload");
                }
                scpSession.put(this, worker, data, file, dir);
            } catch (Exception e) {
                if (fallbackAddress == null) {
                    throw e;
                }
                traceInfo(worker, "SCP upload Exception triggering fallback: "+e.getMessage());

                // Upload file again to the fallback
                traceInfo(worker, "SCP upload to "+scpAddress+ " failed, retrying using "+fallbackAddress);
                fallbackAddress = scpOpenSession(worker, fallbackAddress, "fallback upload");
                scpSession.put(this, worker, data, file, dir);
            }

            // Copy file to running config (equals a load merge)
            while (true) {
                cliRefresh(worker);
                long copystart = tick(0);
                String res = print_line_exec_slow(worker, "copy /noconfirm "+file+" running-config", writeTimeout);
                if (isExecError(res)) {
                    if (fallbackAddress != null && res.contains("No such file or directory")) {
                        // File ended up on wrong physical device, retry using the fallback device
                        traceInfo(worker, "SCP uploaded to wrong device "+scpAddress+ ", retrying fallback");
                        fallbackAddress = scpOpenSession(worker, fallbackAddress, "fallback upload");
                        scpSession.put(this, worker, data, file, dir);
                        continue;
                    }
                    res = stripLineAll(worker, res, "Cryptochecksum ", false);
                    res = stripLineAll(worker, res, " bytes copied in ", true);
                    throw new NedException("copy to running-config failed: "+res);
                } else if (res.contains("Command Ignored, configuration in progress")) {
                    sleep(worker, 1000, " - SCP copy retry");
                    traceInfo(worker, "SCP retrying copy of uploaded config to running-config");
                    continue;
                }
                traceInfo(worker, "SCP copied "+data.length()+" bytes to running-config"+scpSession.msToString(copystart));
                break;
            }

        } catch (Exception e) {
            traceInfo(worker, "SCP upload Exception: "+e.getMessage());
            throw new ExtendedApplyException("commit", "SCP upload ERROR: "+e.getMessage(), true, true);

        } finally {
            scpFinally(worker, file);
        }

        logInfo(worker, "DONE SCP upload "+data.length()+" bytes "+tickToString(start));
    }


    /**
     * Open SCP session
     * @param
     * @return Fallback address or null if none
     * @throws Exception
     */
    private String scpOpenSession(NedWorker worker, String fallbackAddress, String dirbuf) throws Exception {

        // Close previous session if any (used by fallback code)
        scpCloseSession(worker);

        traceInfo(worker, "SCP opening "+dirbuf+" session");
        int th = -1;
        String logfall = fallbackAddress != null ? " [FALLBACK]" : "";
        try {
            setUserSession(worker);
            th = maapi.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);

            // Specify SCP Server device
            String scpServer = device_id;
            if (scpDeviceName != null) {
                scpServer = scpDeviceName;
            }

            // Get scpAddress address and fallbackAddress
            if (fallbackAddress != null) {
                this.scpAddress = fallbackAddress;
                fallbackAddress = null;
            } else {
                fallbackAddress = scpAddressPick(worker, th, scpServer);
            }
            if (this.scpAddress == null) {
                throw new NedException("SCP config error :: missing address in "+scpServer+" device config");
            }

            // Get port
            String scpPort = getDeviceSetting(scpServer, th, "port");
            if (scpPort == null) {
                scpPort = "22";
            }

            // Get authgroup authgroup name and password (decrypted)
            String scpAuthgroup = getDeviceSetting(scpServer, th, "authgroup");
            if (scpAuthgroup == null) {
                throw new NedException("SCP config error :: missing authgroup in "+scpServer+" device config");
            }
            String scpName = getAuthGroupSetting(worker, th, scpAuthgroup, "remote-name");
            if (scpName == null) {
                throw new NedException("SCP config error :: missing remote-name in authgroup "+scpAuthgroup);
            }
            String scpPass = getAuthGroupSetting(worker, th, scpAuthgroup, "remote-password");
            if (scpPass == null) {
                throw new NedException("SCP config error :: missing remote-password in authgroup "+scpAuthgroup);
            }
            scpPass = mCrypto.decrypt(scpPass);

            // Get SCPSession instance
            String log = "SCP using "+scpServer+" at "+this.scpAddress;
            if (scpDevice2Address != null && this.scpAddress.equals(scpDevice2Address)) {
                log += " [Standby]";
            }
            traceInfo(worker, log + logfall);
            scpSession = SCPSession.getInstance(this, worker, scpServer, this.scpAddress, scpPort, scpName, scpPass);
            return fallbackAddress;

        } finally {
            if (th != -1) {
                maapi.finishTrans(th);
            }
        }
    }


    /**
     * Close SCP Session
     * @param
     */
    private void scpCloseSession(NedWorker worker) {
        if (scpSession == null) {
            return;
        }
        try {
            scpSession.closeInstance(this, worker, scpAddress);
        } catch (Exception ignore) {
            // Ignore Exception
        }
        scpSession = null;
        scpAddress = "";
    }


    /**
     * Clean up SCP
     * @param
     */
    private void scpFinally(NedWorker worker, String file) {
        try {
            scpCloseSession(worker);
            lastTimeout = setReadTimeout(worker);
            cliRefresh(worker);
            print_line_exec(worker, "delete /noconfirm "+file);
        } catch (Exception e) {
            // Ignore Exception
        }
    }


    /**
     * Set this.scpAddress and return fallbackAddress or null, if none should be used
     * @param
     * @return Fallback address, null for no retry
     */
    private String scpAddressPick(NedWorker worker, int th, String scpServer) throws Exception {

        // Look up (admin-device) IP address
        String address = getDeviceSetting(scpServer, th, "address");
        this.scpAddress = address;

        // Did not configure a admin backup, can't fallback
        if (scpDeviceName == null || scpDevice2Address == null || contextName == null) {
            return null;
        }

        // Lookup in admin-device CDB if context group is primary or secondary
        int group = -1;
        final String root = "/ncs:devices/device{"+scpDeviceName+"}/config/asa:";
        try {
            // Get failover-group and priority of this group
            String path = root + "context{"+contextName+"}/join-failover-group";
            if (!maapi.exists(th, path)) {
                throw new NedException("!exist");
            }
            group = Integer.parseInt(ConfValue.getStringByValue(path, maapi.getElem(th, path)));
        } catch (Exception e) {
            throw new NedException("failed to read context{"+contextName
                                   +"}/join-failover-group in admin-device "+scpDeviceName+" CDB", e);
        }
        String prio = "primary";
        try {
            String path = root + "failover/group{"+group+"}/priority";
            if (maapi.exists(th, path)) {
                prio = ConfValue.getStringByValue(path, maapi.getElem(th, path));
            }
        } catch (Exception e) {
            throw new NedException("failed to read failover/group{"+group
                                   +"}/priority in admin-device "+scpDeviceName+" CDB", e);
        }

        // "primary" group contexts always use admin-device and never try fallback
        if ("primary".equals(prio)) {
            traceInfo(worker, "SCP /"+contextName+" failover-group "+group+" is on Primary");
            return null;
        }

        //
        // Configured device-lookup, connect to admin-device and find out where context is active
        //
        if (scpDeviceLookup) {
            traceInfo(worker, "SCP looking up /"+contextName+" failover-group "+group+" on admin-device");
            try {
                setupAdminSSH(worker, scpServer, th);
                String showFail = adminSSH.print_line_wait(worker, device_id,
                                                           "show failover | i Group "+group+"|"+scpDevice2Address);
                if (isExecError(showFail)) {
                    throw new NedException(showFail);

                }
                String state = getMatch(showFail, "State[:][ ]+(\\S.+)"); // Note: will pick first(This) Host state
                if ("Active".equals(state)) {
                    traceInfo(worker, "SCP failover-group "+group+" is Active on Primary");
                } else {
                    traceInfo(worker, "SCP failover-group "+group+" is Active on Secondary");
                    this.scpAddress = scpDevice2Address;
                    if ("secadminiffail".equals(simFail)
                        || getMatch(showFail, "admin Interface (.+) Failed") != null) {
                        throw new NedException("Secondary admin Interface: Failed");
                    }
                }
                return null; // Don't use a backup if system show failover worked
            } finally {
                closeAdminSSH(worker);
            }
        }

        //
        // "secondary" group -> Derive all we can using 'show monitor-interface'
        //
        String showMon = print_line_exec(worker, "show monitor-interface");
        if (isExecError(showMon)) {
            traceInfo(worker, "SCP WARNING: failover not supported: "+showMon);
            return null;
        }
        if (showMon.trim().isEmpty()) {
            traceInfo(worker, "SPC NOTICE: failover disabled on device");
            return null;
        }

        // Check which device we are Active on
        Pattern p = Pattern.compile("(\\S+) host[:] (\\S+) \\- Active");
        Matcher m = p.matcher(showMon);
        if (!m.find()) {
            throw new Exception("failed to find Active host");
        }
        String failoverMonIf = m.group(1) + "-" + m.group(2);
        traceInfo(worker, "SCP /"+contextName+" on secondary failover is Active on "+failoverMonIf);

        // #2,4,6 "secondary" group on Primary, fallback to Secondary
        if (failoverMonIf.contains("Primary")) {
            return scpDevice2Address;
        }

        // #5 "secondary" group on Secondary but Primary is not Standby Ready, fallback to Secondary
        if (!showMon.contains("Other host: Primary - Standby Ready")) {
            return scpDevice2Address;
        }

        // #1,3 "secondary" group on Secondary, Use device2-address and fallback to Primary
        this.scpAddress = scpDevice2Address;
        return address;
    }


    /**
     *
     * @param
     * @return
     */
    private String scpDirectory() {
        String directory = scpDir;
        if (directory.contains("%c") && configUrl != null) {
            String configUrlRoot = configUrl.substring(0, configUrl.lastIndexOf('/')+1);
            directory = directory.replace("%c", configUrlRoot);
        } else if (this.contextName != null) {
            directory += this.contextName+"/";
        }
        return directory;
    }


    /**
     *
     * @param
     * @return
     */
    private String scpFile(NedWorker worker, boolean writing) {
        String file = scpFile;
        if (file.contains("%d") || file.contains("%t")) {
            Date date = new Date();
            if (file.contains("%d")) {
                DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
                file = file.replace("%d", dateFormat.format(date));
            }
            if (file.contains("%t")) {
                DateFormat dateFormat = new SimpleDateFormat("HH.mm.ss");
                file = file.replace("%t", dateFormat.format(date));
            }
        }
        if (file.contains("%i")) {
            file = file.replace("%i", Integer.toString(worker.getToTransactionId()));
        }
        if (file.contains("%x")) {
            file = writing ? file.replace("%x", "w") : file.replace("%x", "r");
        }
        if (contextName != null) {
            file = file.replace("%c", contextName);
        } else if (isAdminContext()) {
            file = file.replace("%c", "admin");
        } else {
            file = file.replace("%c", "system");
        }
        return file;
    }


    /**
     *
     * @param
     * @throws Exception
     */
    private boolean scpSendConfig(NedWorker worker, int cmd, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {
        try {
            if (data.trim().startsWith("ssh timeout ") || data.trim().startsWith("no ssh timeout")) {
                // Configure increased ssh timeout command in CLI before SCP upload
                String sshTimeout = getMatch(data, "((?:no )?ssh timeout \\d+)");
                if (sshTimeout != null) {
                    traceInfo(worker, "Updating ssh timeout in CLI shell before SCP");
                    sendConfig(worker, cmd, sshTimeout+"\n");
                    data = data.replace(sshTimeout+"\n", "");
                }
            }
            if (!data.trim().isEmpty()) {
                scpPutConfig(worker, data);
                secrets.apply(worker, data);
            }
            return true;

        } catch (Exception e) {
            if ("disabled".equals(scpCliFallback)) {
                throw e;
            }
            logError(worker, "SCP upload failed, retrying using CLI method");
            lastTimeout = setReadTimeout(worker);
            return false;
        }
    }


    /**
     *
     * @param
     * @throws Exception
     */
    private void sessionSendConfig(NedWorker worker, int cmd, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {
        final long start = tick(0);

        String[] lines = data.split("\n");
        logInfo(worker, "BEGIN sending (session) "+lines.length+" line(s)");

        // Enter config session
        print_line_wait_oper(worker, "configure session nso");

        // Send commands in session, one line at a time
        try {
            for (int i = 0; i < lines.length; i++) {
                String trimmed = lines[i].trim();
                if (trimmed.isEmpty() || trimmed.startsWith("!")) {
                    continue;
                }

                // Bulk mode, send chunk of commands before checking replies
                if (chunkSize > 1) {
                    int e;
                    for (e = i; e < lines.length; e++) {
                        int bulk = isBulkConfig(lines[e]);
                        if (bulk == 0) {
                            break;
                        }
                        if (bulk == 1) {
                            continue;
                        }
                        for (e = e + 1; e < lines.length; e++) {
                            if (isTopExit(lines[e])) {
                                break;
                            }
                        }
                    }
                    if (e - i > 1) {
                        sendBulkConfig(worker, cmd, lines, i, e);
                        i = e - 1;
                        continue;
                    }
                }

                // Update timeout
                lastTimeout = resetReadTimeout(worker, lastTimeout);

                // Send line to device
                print_line_wait_session(worker, cmd, trimmed, i);
            }
        } catch (ApplyException e) {
            print_line_wait_oper(worker, "abort");
            if (configSessionMode.equals("enabled-fallback")) {
                String errmsg = e.getMessage();
                if (errmsg.contains("cannot be modified from configuration session")) {
                    traceInfo(worker, "SESSION: ERROR '"+errmsg+"' - fallback to configure terminal");
                    sendConfig(worker, cmd, data);
                    return;
                }
            }
            logInfo(worker, "DONE "+nedCmdFullName(cmd)+" - ERROR sending (session): "+stringQuote(e.getMessage()));
            throw e;
        }

        // Commit session (also exits config mode)
        print_line_wait_oper_slow(worker, "commit noconfirm", this.writeTimeout);
        logInfo(worker, "DONE sending (session) "+tickToString(start));
    }


    /**
     * Send config to device
     * @param
     * @throws Exception
     */
    private String delayedConfig;
    private void sendConfig(NedWorker worker, int cmd, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {
        String orgdata = data;

        String[] lines = data.split("\n");
        if (lines.length == 0) {
            logInfo(worker, "BEGIN-DONE sending ignored, 0 lines");
            return;
        }

        final long start = tick(0);
        logInfo(worker, "BEGIN sending "+lines.length+" line(s), [chunk "+chunkSize+"]");

        // Enter config mode and (optionally) changeto system context
        if (!enterConfig(worker, cmd, true)) {
            throw new NedException("sendConfig() :: Failed to enter config mode");
        }

        // Send config to the device, line by line
        String meta = "";
        boolean isAtTop = true;
        delayedConfig = "";
        modeStack = new ModeStack();
        try {
            // Apply one line at a time
            for (int i = 0; i < lines.length; i++) {
                String trimmed = lines[i].trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                // Ignore sending meta-data to device, cache it temporarily
                if (trimmed.startsWith(META_DATA)) {
                    meta += (trimmed + "\n");
                    continue;
                }
                modeStack.update(lines[i]);

                // Bulk mode, send chunk of commands before checking replies
                if (chunkSize > 1) {
                    int e;
                    for (e = i; e < lines.length; e++) {
                        int bulk = isBulkConfig(lines[e]);
                        if (bulk == 0) {
                            break;
                        }
                        if (bulk == 1) {
                            continue;
                        }
                        for (e = e + 1; e < lines.length; e++) {
                            if (isTopExit(lines[e])) {
                                break;
                            }
                        }
                    }
                    if (e - i > 1) {
                        isAtTop = sendBulkConfig(worker, cmd, lines, i, e);
                        i = e - 1;
                        continue;
                    }
                }

                // Ignore all other comments
                if (trimmed.startsWith("!")) {
                    continue;
                }

                // Update timeout
                lastTimeout = resetReadTimeout(worker, lastTimeout);

                // Send line to device
                isAtTop = print_line_wait(worker, cmd, trimmed, 0, meta, i);
                meta = "";
            }

            // Make sure we have exited from all submodes
            if (!isAtTop) {
                moveToTopConfig(worker);
            }

            // Apply delayed config (simple commands, can't change mode, cant be modified)
            String[] delayedLines = delayedConfig.trim().split("\n");
            for (int i = 0; i < delayedLines.length; i++) {
                if (!delayedLines[i].trim().isEmpty()) {
                    traceInfo(worker, "Sending delayed config:");
                    print_line_wait(worker, cmd, delayedLines[i].trim(), 0, null, i);
                }
            }
        } catch (ApplyException e) {
            if (!e.isAtTop) {
                moveToTopConfig(worker);
            }
            if (e.inConfigMode) {
                exitConfig(worker);
            }
            logInfo(worker, "DONE "+nedCmdFullName(cmd)+" - ERROR sending: "+stringQuote(e.getMessage()));
            throw e;
        }

        // Exit config mode
        exitConfig(worker);

        // All commands accepted by device, prepare caching of secrets
        secrets.apply(worker, orgdata);

        logInfo(worker, "DONE sending "+lines.length+" line(s) [chunk "+chunkSize+"] "+tickToString(start));
    }


    /**
     *
     * @param
     * @return
     */
    private int isBulkConfig(String line) {
        String cmd = line.startsWith("no ") ? line.substring(3) : line;

        // Non-mode lists
        String[] nonModeLists = {
            "access-list "
        };
        for (int n = 0; n < nonModeLists.length; n++) {
            if (cmd.startsWith(nonModeLists[0])) {
                return 1;
            }
        }

        // Mode lists
        String[] modeLists = {
            "class-map ",
            "object-group ",
            "policy-map "
        };
        for (int n = 0; n < modeLists.length; n++) {
            if (line.startsWith(modeLists[0])) {
                return 2;
            }
            if (line.startsWith("no "+modeLists[0])) {
                return 1;
            }
        }

        return 0;
    }


    /**
     *
     * @param
     * @return
     * @throws Exception
     */
    private boolean sendBulkConfig(NedWorker worker, int cmd, String[] lines, int start, int end)
        throws NedException, IOException, SSHSessionException, ApplyException {
        int n;
        int length = end - start;

        traceInfo(worker, "BULK SENDING "+length+" lines [chunk "+chunkSize+"]");
        //for (int i = start; i < end; i++) traceVerbose(worker, "   bulk["+i+"] = "+lines[i]);

        for (int i = start; i < end; i += chunkSize) {

            // Copy in up to chunkSize config commands in chunk
            String chunk = "";
            int num;
            for (num = 0, n = i; n < end && n < (i + chunkSize); n++) {
                String line = lines[n];
                if (line == null || line.isEmpty()) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.startsWith(META_DATA)) {
                    continue;
                }
                if (trimmed.equals("!")) {
                    continue;
                }
                chunk += (line + "\n");
                num++;
            }

            // Send chunk of X lines to device
            traceVerbose(worker, "  BULK SENDING lines "+i+"-"+(i+num-1)+" / "+length);
            session.print(chunk);

            // Check device reply of one line at the time
            for (n = i; n < end && n < (i + chunkSize); n++) {
                String line = lines[n];
                if (line == null || line.isEmpty()) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.startsWith(META_DATA)) {
                    continue;
                }
                if (trimmed.equals("!")) {
                    continue;
                }

                // Update timeout
                lastTimeout = resetReadTimeout(worker, lastTimeout);

                // Check device echo and possible input error
                noprint_line_wait(worker, cmd, trimmed);
            }
        }
        return true;
    }


    /**
     * Enter config mode
     * @param
     * @return
     * @throws Exception
     */
    protected boolean enterConfig(NedWorker worker, int cmd, boolean changetoSystem)
        throws NedException, IOException, SSHSessionException {

        // Enter config mode
        traceVerbose(worker, "SENDING_OPER: 'config t'");
        session.print("config t\n");
        session.expect("config t", worker);

        // Wait for prompt
        NedExpectResult res = session.expect(ec, worker);

        // Got: Aborted | Error | syntax error | error:
        if (res.getHit() > 2) {
            worker.error(cmd, res.getText());
            return false;
        }

        // Got: Do you want to kill that session and continue
        else if (res.getHit() == 0) {
            session.print("yes\n");
            res = session.expect(ec2, worker);
            // Got: Aborted | Error | syntax error | error:
            if (res.getHit() > 2) {
                worker.error(cmd, res.getText());
                return false;
            }
        }

        // Multi-context admin - always start in system (context)
        if (changetoSystem && isAdminContext() && !changeto_context(worker, "system", false)) {
            worker.error(cmd, "Failed to enter system context");
            return false;
        }

        return true;
    }


    /**
     *
     * @param
     * @throws Exception
     */
    private void moveToTopConfig(NedWorker worker)
        throws IOException, SSHSessionException {
        NedExpectResult res;

        traceVerbose(worker, "moveToTopConfig()");

        while (true) {
            traceVerbose(worker, "SENDING_OPER: 'exit'");
            session.print("exit \n");
            session.expect("exit", worker);
            res = session.expect(config_prompt_patterns, worker);
            if (res.getHit() == 0) {
                return;
            }
        }
    }


    /**
     * Exit config mode
     * @param
     * @throws Exception
     */
    protected void exitConfig(NedWorker worker) throws IOException, SSHSessionException {

        // Send ENTER to begin by checking our mode
        traceVerbose(worker, "exitConfig() - sending newline");
        session.print("\n");

        // Then keep sending exit until we leave config mode
        final Pattern[] cprompt = new Pattern[] {
            Pattern.compile(CONFIG_PROMPT),
            Pattern.compile("\\A\\S*#")
        };
        while (true) {
            NedExpectResult res = session.expect(cprompt, worker);
            if (res.getHit() == 1) {
                traceVerbose(worker, "Exited config mode");
                return;
            }
            traceVerbose(worker, "SENDING_OPER: 'exit'");
            session.print("exit\n");
            session.expect("exit", worker);
        }
    }


    /**
     * Send command to device, wait for echo and reply
     * @param
     * @return
     * @throws NedException, IOException, SSHSessionException, ApplyException
     */
    private boolean print_line_wait(NedWorker worker, int cmd, String line,
                                    int retrying, String meta, int num)
        throws NedException, IOException, SSHSessionException, ApplyException {
        String orgLine = line;

        traceVerbose(worker, "SENDING["+nedCmdName(cmd)+num+"]: '"+line+"'");

        // password -> may be maapi encrypted
        boolean decrypted = false;
        if (meta != null &&
            (meta.contains(" :: secret") || meta.contains(" :: support-encrypted-password"))) {
            String decryptedLine = decryptPassword(worker, line);
            if (!decryptedLine.equals(line)) {
                decrypted = true;
                if (trace) {
                    worker.trace("*" + orgLine + "\n\n", "out", device_id);
                    if (!logVerbose) {
                        session.setTracer(null);
                    }
                }
                line = decryptedLine;
            }
            // password -> must prepend '?' with CTRL-V
            session.print(stringInsertCtrlV(line) + "\n");
        }

        // all other lines -> send normal
        else {
            session.print(line+"\n");
        }

        // Wait for echo
        if (line.length() > 500) {
            traceVerbose(worker, "Waiting for echo of long line = "+line.length()+" characters");
            expect_echo(line, worker);
        } else {
            session.expect(new String[] {
                    Pattern.quote(line),
                    ".*INFO: ASAv platform license state is Licensed.*"},
                worker);
        }

        // Enable tracing if disabled due to sending decrypted clear text passwords
        if (decrypted) {
            if (trace) {
                session.setTracer(worker);
                worker.trace("*" + orgLine + "\n", "out", device_id);  // simulated echo
            }
            line = orgLine;
        }

        // Wait for prompt
        NedExpectResult res = session.expect(PLW, worker);
        if (res.getHit() < 2) {
            // Questions
            if (isCliError(worker, cmd, res.getText(), line) < 0) {
                throw new ApplyException(line, res.getText(), false, false);
            }
            // Send reply and wait for prompt
            if (res.getHit() == 0) {
                session.print("c");
            } else {
                session.print("yes\n");
            }
            res = session.expect(PLW, worker);
        }
        else if (res.getHit() == 2) {
            // Got: 'WARNING: Configuration syncing is in progress'
            traceInfo(worker, "SENDING 'no' and waiting for 'End of Configuration Replication'");
            session.print("no\n");
            session.expect("End Configuration Replication to mate", worker);
            session.print("\n");
            session.expect(PLW, worker);
            traceInfo(worker, "Retrying, attempt #" + (retrying + 1));
            return print_line_wait(worker, cmd, orgLine, retrying, meta, num);
        }

        // Verify sub-mode
        boolean isAtTop;
        if (res.getHit() == 3 || res.getHit() == 4)
            isAtTop = true;
        else if (res.getHit() == 5)
            isAtTop = false;
        else {
            throw new ApplyException(line, "exited from config mode", false, false);
        }

        // When initializing new contexts, ignore all errors from init script
        String reply = res.getText();
        if (isAdminContext() && line.startsWith("config-url ")) {
            String[] lines = reply.split("\n|\r");
            for (int i = 0 ; i < lines.length ; i++) {
                if (lines[i].contains("Creating context with default config")) {
                    return isAtTop;
                }
                if (lines[i].contains("INFO: Context ")
                    && lines[i].contains(" was created")) {
                    return isAtTop;
                }
            }
        }

        // sla monitor patch (need to remove schedule before modifying)
        String match;
        if (reply.contains("Entry already running and cannot be modified")
            && (match = getMatch(line, "sla monitor (\\d+)")) != null) {
            String show = print_line_exec(worker, "show run sla monitor " + match);
            String schedule = findLine(show, "sla monitor schedule " + match);
            if (schedule != null) {
                print_line_wait(worker, cmd, "no " + schedule, 0, null, num);
                traceInfo(worker, "Adding delayed config: " + stringQuote(schedule));
                delayedConfig += schedule + "\n";
                return print_line_wait(worker, cmd, line, retrying, null, num);
            }
        }

        //
        // Check device reply [ < 0 = ERROR, 0 = success, > 0 retries ]
        //
        int error = isCliError(worker, cmd, reply, line);
        if (error == 0) {
            return isAtTop;
        } else if (error < 0) {
            throw new ExtendedApplyException(line+"\r\n", reply.trim()+modeStack.toString(), isAtTop, true);
        }

        //
        // Retrying
        //

        // Already tried enough, give up
        if (++retrying > error) {
            throw new ExtendedApplyException(line, reply, isAtTop, true);
        }

        // Sleep a second (max 60 times)
        if (retrying == 0) {
            worker.setTimeout(10*60*1000);
        }
        sleep(worker, 1000, null);

        // Retry line once more
        traceInfo(worker, "Retrying, attempt #" + retrying);
        return print_line_wait(worker, cmd, line, retrying, meta, num);
    }


    /**
     *
     * @param
     * @throws Exception
     */
    private void noprint_line_wait(NedWorker worker, int cmd, String trimmed)
        throws NedException, IOException, SSHSessionException, ApplyException {
        NedExpectResult res;

        // First, wait for echo
        try {
            if (trimmed.length() > 500) {
                traceVerbose(worker, "Waiting for echo of long line = "+trimmed.length()+" characters");
                expect_echo(trimmed, worker);
            } else {
                session.expect(new String[] { Pattern.quote(trimmed) }, worker);
            }
        } catch (Exception e) {
            // Possibly a timeout, try return the input data from the buffer
            res = session.expect(new Pattern[] { Pattern.compile(".*", Pattern.DOTALL) }, true, 0, worker);
            String msg = e.getMessage() + " waiting for "+stringQuote(trimmed);
            String match = res.getMatch().trim();
            if (match.isEmpty()) {
                msg += ", no response from device";
            } else {
                msg += (", blocked on "+stringQuote(match));
            }
            traceInfo(worker, "ERROR: "+msg);
            throw new ApplyException(trimmed, msg, true, true);
        }

        // Second, wait for prompt and check if question or exit from config mode
        res = session.expect(PLW, worker);
        switch (res.getHit()) {
        case 0: // Continue?[confirm]
        case 1: // ? [yes/no]
        case 2: // WARNING: Configuration syncing is in progress
            throw new ApplyException(trimmed, "Internal ERROR: device prompted", true, true);
        case 3: // (cfg)
        case 4: // (config)
        case 5: // (xxx)
            break;
        default: // exec prompt
            throw new ApplyException(trimmed, "exited from config mode", false, false);
        }

        // Third, check device reply [ < 0 = ERROR, 0 = success, > 0 retries ]
        String reply = res.getText();
        int error = isCliError(worker, cmd, reply, trimmed);
        if (error == 0) {
            return;
        } else if (error < 0) {
            throw new ExtendedApplyException(trimmed, reply, true, true);
        } else {
            throw new ApplyException(trimmed, "Internal ERROR: retry-command", true, true);
        }
    }


    /**
     *
     * @param
     * @throws Exception
     */
    private void print_line_wait_session(NedWorker worker, int cmd, String trimmed, int num)
        throws NedException, IOException, SSHSessionException, ApplyException {

        traceVerbose(worker, "SENDING_SESSION["+nedCmdName(cmd)+num+"]: '"+trimmed+"'");

        // Send line
        session.print(trimmed+"\n");

        // Wait for echo
        if (trimmed.length() > 500) {
            traceVerbose(worker, "Waiting for echo of long session line = "
                         +trimmed.length()+" characters");
            expect_echo(trimmed, worker);
        } else {
            session.expect(new String[] { Pattern.quote(trimmed) }, worker);
        }

        // Wait for prompt
        NedExpectResult res = session.expect(config_prompt_patterns, worker);
        String reply = res.getText().trim();

        //
        // Check device reply [ < 0 = ERROR, 0 = success, > 0 retries ]
        //
        int error = isCliError(worker, cmd, reply, trimmed);
        if (error != 0) {
            throw new ExtendedApplyException(trimmed, reply, true, true);
        }
    }


    /**
     *
     * @param
     * @return
     */
    private String stringInsertCtrlV(String line) {
        StringBuilder result = new StringBuilder();
        StringCharacterIterator iterator =
            new StringCharacterIterator(line);
        char c1 = iterator.current();

        while (c1 != CharacterIterator.DONE) {
            if (c1 == '?') {
                result.append("\u0016");
            }
            result.append(c1);
            c1 = iterator.next();
        }
        return result.toString();
    }


    /**
     *
     * @param
     * @return
     */
    private int isCliError(NedWorker worker, int cmd, String reply, String line) {
        int n;
        String trimmed = line.trim();  // Parachute
        String replyT = reply.trim();

        // Retry
        if (reply.toLowerCase().contains("wait for it to complete")) {
            return 60;
        }
        if (reply.contains("Command Failed. Configuration in progress...")) {
            return 10; // config-url
        }
        if (reply.toLowerCase().contains("object is being used")) {
            return 1;
        }

        // Special cases ugly patches
        if (trimmed.startsWith("no ip address ")
            && reply.contains("Invalid address")) {
            // Happens when IP addresses already deleted on interface
            traceInfo(worker, "Ignoring '"+line+"' command");
            return 0;
        }
        if (trimmed.equals("no duplex")
            && reply.contains("Invalid input detected at")) {
            // Happens when 'no media-type' deletes duplex config on device
            traceInfo(worker, "Ignoring '"+line+"' command");
            return 0;
        }
        if (trimmed.startsWith("delete /noconfirm ")
            && reply.contains("No such file or directory")) {
            // Happens when auto-deleting context config-url
            traceInfo(worker, "Ignoring delete of non-existing context config-url file");
            return 0;
        }
        if (trimmed.startsWith("no ip address")
            && reply.contains("Invalid input detected at")) {
            // Happens for cli-show-no annotation
            traceInfo(worker, "Ignoring '"+line+"' (cli-show-no)");
            return 0;
        }

        // The following warnings is an error -> abort transaction
        String[] warningfail = {
            "WARNING: IP address .* and netmask .* inconsistent"
        };
        for (n = 0; n < warningfail.length; n++) {
            if (findString(warningfail[n], reply) >= 0) {
                traceInfo(worker, "ERROR - matched warningfail: "+replyT);
                return -1;
            }
        }

        // The following strings treated as warnings -> ignore
        String[] errignore = {
            "Warning: \\S+.*",
            "WARNING: \\S+.*",
            "AAA: Warning",
            "name length exceeded the recommended length of .* characters",
            "A profile is deemed incomplete until it has .* statements",
            "Interface description was set by failover and cannot be changed",
            "Specified (\\S+) (\\S+) does not exist"

        };
        for (n = 0; n < errignore.length; n++) {
            if (findString(errignore[n], reply) >= 0) {
                traceInfo(worker, "Ignoring warning: "+replyT);
                return 0;
            }
        }

        for (n = 0; n < errfail.length; n++) {
            if (findString(errfail[n], reply.toLowerCase()) >= 0) {
                // Ignore all new errors when rollbacking due to abort/revert
                if (cmd == NedCmd.ABORT_CLI || cmd == NedCmd.REVERT_CLI) {
                    traceInfo(worker, "Ignoring abort/revert ERROR: "+replyT);
                    return 0;
                }
                traceInfo(worker, "ERROR SENDING - matched '"+replyT+"'");
                return -1;
            }
        }

        // Success
        return 0;
    }


    /**
     *
     * @param
     * @return
     */
    private String decryptPassword(NedWorker worker, String line) {
        Pattern p = Pattern.compile("( \\$[48]\\$[^\\s]*)"); // " $4$<key>" || " $8<key>"
        Matcher m = p.matcher(line);
        while (m.find()) {
            String password = line.substring(m.start() + 1, m.end());
            try {
                String decrypted = mCrypto.decrypt(password);
                line = line.substring(0, m.start()+1)
                    + decrypted
                    + line.substring(m.end(), line.length());
                traceVerbose(worker, "transformed => decrypted MAAPI password: '"+password+"' to '"+decrypted+"'");
            } catch (MaapiException e) {
                logError(worker, "mCrypto.decrypt("+password+") exception ERROR", e);
                return line;
            }
            m = p.matcher(line);
        }
        return line;
    }


    /**
     *
     * @param
     * @return
     */
    static private String nedCmdName(int cmd) {
        if (cmd == NedCmd.ABORT_CLI) {
            return "abort ";
        }
        if (cmd == NedCmd.REVERT_CLI) {
            return "revert ";
        }
        return "";
    }


    /**
     *
     * @param
     * @return
     */
    static private String nedCmdFullName(int cmd) {
        if (cmd == NedCmd.ABORT_CLI) {
            return "ABORT";
        }
        if (cmd == NedCmd.REVERT_CLI) {
            return "REVERT";
        }
        return "APPLY-CONFIG";
    }


    /*
     **************************************************************************
     * persist
     **************************************************************************
     */

    /**
     * Persist (save) config on device
     * @param
     * @return
     * @throws Exception
     */


    /*
     **************************************************************************
     * commit
     **************************************************************************
     */

    /**
     * Commit config
     * @param
     * @throws Exception
     */
    @Override
    public void commit(NedWorker worker, int timeout) throws Exception {
        final long start = tick(0);
        if (trace) {
            session.setTracer(worker);
        }
        logInfo(worker, "BEGIN COMMIT");

        // Reconnect to device if remote end closed connection due to being idle
        if (session.serverSideClosed()) {
            reconnectDevice(worker);
        }

        // Save config
        saveConfig(worker);

        // Cache secrets
        if (secrets.needUpdate()) {
            traceInfo(worker, "[SECRETS] New secrets, caching encrypted entries");
            String config = getConfig(worker);
            lastTransformedConfig = modifyInput(worker, config);
        }

        logInfo(worker, "DONE COMMIT "+tickToString(start));
        worker.commitResponse();
    }


    /**
     * Save configuration on device
     * @param
     * @throws Exception
     */
    private void saveConfig(NedWorker worker) throws Exception {
        final long start = nedReportProgress(worker, "saving config...", 0);
        try {

            // Saving to startup-config disabled
            if ("disabled".equals(writeMemoryMode)) {
                // Don't save on device, but still check config archive
            }

            // No contexts or single context
            else if (isSingleContext()) {
                print_line_wait_oper(worker, "write memory");
            }

            // Save all contexts
            else if (modifiedContextList == null || writeMemoryMode.equals("on-commit-all")) {
                changeto_context(worker, "system", true);
                print_line_wait_oper(worker, "write memory all /noconfirm");
            }

            // Save modified context(s) only
            else {
                // Always save system context
                traceInfo(worker, "Saving /system context");
                changeto_context(worker, "system", true);
                print_line_wait_oper(worker, "write memory");

                // Save user contexts individually
                for (int n = 0; n < modifiedContextList.size(); n++) {
                    String context = modifiedContextList.get(n);
                    String reply = print_line_exec(worker, "changeto context "+context);
                    if (!isExecError(reply)) {
                        traceInfo(worker, "Saving /"+context+" context");
                        print_line_wait_oper(worker, "write memory");
                    }
                }
            }

            // Archive config
            configArchive.archive(worker);

            // Done
            nedReportProgress(worker, "saving config ok", start);

        } catch (Exception e) {
            nedReportProgress(worker, "saving config error", start);
            throw e;
        }
    }


    /*
     **************************************************************************
     * abort
     **************************************************************************
     */

    /**
     * apply failed, rollback config
     * @param
     * @throws Exception
     */
    @Override
    public void abort(NedWorker worker, String data) throws Exception {
        final long start = tick(0);
        if (trace) {
            session.setTracer(worker);
        }
        logInfo(worker, "BEGIN ABORT");

        // Apply the abort
        doApplyConfig(worker, NedCmd.ABORT_CLI , data);

        logInfo(worker, "DONE ABORT "+tickToString(start));
        worker.abortResponse();
    }


    /*
     **************************************************************************
     * revert
     **************************************************************************
     */

    /**
     * Revert
     * @param
     * @throws Exception
     */
    @Override
    public void revert(NedWorker worker, String data) throws Exception {
        final long start = tick(0);
        if (trace) {
            session.setTracer(worker);
        }
        logInfo(worker, "BEGIN REVERT");

        // Apply the revert
        doApplyConfig(worker, NedCmd.REVERT_CLI, data);

        // Save config
        saveConfig(worker);

        logInfo(worker, "DONE REVERT "+tickToString(start));
        worker.revertResponse();
    }


    /*
     **************************************************************************
     * cleanup
     **************************************************************************
     */

    /**
     * Cleanup NED, called by close
     * @throws Exception
     */
    protected void cleanup() throws Exception {
        logInfo(null, "BEGIN CLEANUP");
        closeAdminSSH(null);
        scpCloseSession(null);
        logInfo(null, "DONE CLEANUP");
    }


    /**
     * Close admin SSH
     * @param
     */
    private void closeAdminSSH(NedWorker worker) {
        if (adminSSH == null) {
            return;
        }
        try {
            adminSSH.closeInstance(worker, device_id);
        } catch (Exception ignore) {
            // Ignore Exception
        }
        adminSSH = null;
    }


    /*
     **************************************************************************
     * command
     **************************************************************************
     */


    /**
     * Run command(s) on device.
     * From ncs_cli: devices device <dev> live-status exec any "command"
     * @param
     * @throws Exception
     */
    @Override
    public void command(NedWorker worker, String cmdName, ConfXMLParam[] p) throws Exception {
        if (trace) {
            session.setTracer(worker);
        }

        // Prepare command
        String cmd = nedCommand.prepare(worker, cmdName, p);

        // internal - show outformat raw
        String reply;
        if (cmd.equals("show outformat raw")) {
            reply = "\nNext dry-run will show raw (unmodified) format.\n";
            showRaw = true;
        }

        // internal - show ned-settings
        else if (cmd.equals("show ned-settings")) {
            reply = "\n"+nedSettings.dumpAll();
        }

        // internal - set asamodel
        else if (cmd.startsWith("set asamodel ")) {
            asamodel = cmd.substring(13);
            reply = "\nasamodel set to '"+asamodel+"'";
        }

        // internal - secrets resync
        else if (cmd.equals("secrets resync")) {
            secrets.enableReSync();
            modifyInput(worker, getConfig(worker));
            reply = "\nRe-synced all cached secrets.\n";
        }

        // internal - fail <phase>
        else if (cmd.startsWith("fail ")) {
            simFail = cmd.substring(5).trim();
            reply = "\nfail set to: '"+simFail+"'\n";
        }

        // internal operpath
        else if (isNetsim() && cmd.startsWith("operpath ")) {
            String id = cmd.trim().substring(9).trim();
            try {
                String operRoot = "/ncs:devices/ncs:device{"
                    +device_id
                    +"}/ncs:ned-settings/secrets:secrets/secret";
                ConfPath cp = new ConfPath(operRoot+"{"+id+"}");
                reply = "\noperpath = "+cp.toString()+"\n";
            } catch (Exception e) {
                throw new NedException("operpath "+id+" EXCEPTION: ", e);
            }
        }

        // internal - sync-from-file <path/file>
        else if (cmd.startsWith("sync-from-file ")) {
            syncFile = cmd.trim().substring(15).trim();
            reply = "\nNext sync-from will use file = " + syncFile + "\n";
        }

        // Device command
        else {
            nedCommand.execute(worker, cmd);
            return;
        }

        // Internal command reply
        logInfo(worker, "COMMAND - internal: "+stringQuote(cmd));
        traceInfo(worker, reply);
        worker.commandResponse(new ConfXMLParam[] { new ConfXMLParamValue("asa-stats", "result", new ConfBuf(reply))});
    }


    /**
     * Send CTRL-Z and wait for prompt
     * @param
     * @throws Exception
     */
    protected void exitPrompting(NedWorker worker) throws IOException, SSHSessionException {

        // Wait for prompt
        final Pattern[] cprompt = new Pattern[] {
            Pattern.compile(CONFIG_PROMPT),
            Pattern.compile("\\A\\S*#"),
            // Question patterns:
            Pattern.compile("\\S+:\\s*$"),
            Pattern.compile("\\S+\\][\\?]?\\s*$")
        };
        while (true) {
            traceInfo(worker, "SENDING CTRL-Z");
            session.print("\u001a");
            NedExpectResult res = session.expect(cprompt, worker);
            if (res.getHit() <= 1) {
                traceVerbose(worker, "Got prompt ("+res.getHit()+")");
                break;
            }
        }
    }


    /*
     **************************************************************************
     * isAlive
     **************************************************************************
     */

    /**
     * Check if server side is closed
     *
     */
    @Override
    public boolean isAlive() {
        if (session == null) {
            logInfo(null, "BEGIN-DONE IS-ALIVE = false (null session)");
            return false;
        }
        if ("isalive".equals(simFail)) {
            logInfo(null, "BEGIN-DONE IS-ALIVE = false (simulated)");
            this.close();
            return false;
        }
        boolean alive = (session.serverSideClosed() == false);
        logInfo(null, "BEGIN-DONE IS-ALIVE = "+alive);
        return alive;
    }


    /*
     **************************************************************************
     * keepAlive
     **************************************************************************
     */

    /**
     * This method is invoked periodically to keep an connection
     * alive. If false is returned the connection will be closed using the
     * close() method invocation.
     *
     * @param worker
     */
    public boolean keepAlive(NedWorker worker) {
        final long start = tick(0);
        if (trace) {
            session.setTracer(worker);
        }
        logInfo(worker, "BEGIN KEEP-ALIVE");
        boolean alive = true;
        try {
            cliRefresh(worker);
        } catch (Exception e) {
            alive = false;
            logError(worker, "KEEP_ALIVE ERROR: "+e.getMessage(), e);
        }
        logInfo(worker, "DONE KEEP-ALIVE = "+alive+" "+tickToString(start));
        return alive;
    }


    /**
     * Refresh CLI
     * @throws Exception
     */
    private void cliRefresh(NedWorker worker) throws Exception {
        if (session.serverSideClosed()) {
            // Reconnect to device if remote end closed connection due to being idle
            reconnectDevice(worker);
        } else {
            traceVerbose(worker, "Sending newline");
            session.println("");
            traceVerbose(worker, "Waiting for prompt");
            session.expect(new String[] { CONFIG_PROMPT, EXEC_PROMPT }, worker);
        }
    }


    /**
     * Reconnect to device using connector
     * @throws NedException
     */
    private void reconnectDevice(NedWorker worker) throws NedException {
        traceInfo(worker, "Server side closed, reconnecting");
        try {
            connectorReconnectDevice(worker);
        } catch (Exception e) {
            throw new NedException("Failed to reconnect :: "+e.getMessage(), e);
        }
    }


    /*
     **************************************************************************
     * NedSecrets
     **************************************************************************
     */

    /**
     * Used by NedSecrets to check whether a secret is cleartext or encrypted.
     * Method must be implemented by all NED's which use NedSecrets.
     * @param secret - The secret
     * @return True if secret is cleartext, else false
     */
    @Override
    public boolean isClearText(String secret) {
        String trimmed = secret.trim();
        // encrypted
        if (secret.matches("[0-9a-f]{2}(:([0-9a-f]){2})+")) {
            return false;   // aa:11 .. :22:bb
        }
        if (trimmed.contains(" encrypted")) {
            return false;  // XXX encrypted
        }
        if (trimmed.contains(" pbkdf2")) {
            return false;  // XXX pbkdf2
        }
        if (secret.startsWith("password ")) {
            return false;  // password XXX
        }
        if (trimmed.endsWith(" 7")) {
            return false;   // XXX 7
        }
        if (secret.equals("*****")) {
            return false;
        }
        if (trimmed.endsWith(" hashed")) {
            return false;
        }

        // cleartext
        if (!trimmed.contains(" ")) {
            return true;   // XXX
        }
        if (trimmed.charAt(0) == '0') {
            return true;   // 0 XXX
        }
        if (secret.startsWith("clear ")) {
            return true;  // clear XXX
        }
        if (trimmed.endsWith(" 0")) {
            return true;   // XXX 0
        }

        // Default to encrypted
        return false;      // 5 XXX, 6 XXX, 7 XXX
    }


    /*
     **************************************************************************
     * Common utility methods
     **************************************************************************
     */


    /**
     * Trace debug message (NOTE: same as traceVerbose)
     * @param
     *
     */
    public void traceDebug(NedWorker worker, String info) {
        if (devTraceLevel >= TRACE_DEBUG) {
            traceInfo(worker, info);
        }
    }


    /**
     * Trace debug2 message
     * @param
     *
     */
    public void traceDebug2(NedWorker worker, String info) {
        if (devTraceLevel >= TRACE_DEBUG2) {
            traceInfo(worker, info);
        }
    }


    /**
     * Trace debug3 message
     * @param
     *
     */
    public void traceDebug3(NedWorker worker, String info) {
        if (devTraceLevel >= TRACE_DEBUG3) {
            traceInfo(worker, info);
        }
    }


    /**
     * Report progress with Verbosity NORMAL
     */
    private long nedReportProgress(NedWorker worker, String msg, long lastTime) {
        return reportProgress(worker, Verbosity.NORMAL, msg, lastTime);
    }


    /**
     * Set user session
     * @throws Exception
     */
    private void setUserSession(NedWorker worker) throws Exception {
        try {
            maapi.getMyUserSession();
        } catch (Exception ignore) {
            traceInfo(worker, "Maapi user session set to 1");
            maapi.setUserSession(1);
        }
    }


    /**
     * Attach to Maapi
     * @param
     * @throws NedException
     */
    private void maapiAttach(NedWorker worker, int fromTh, int toTh) throws NedException {
        try {
            int usid = worker.getUsid();
            maapi.attach(fromTh, 0, usid);
            maapi.attach(toTh, 0);
            traceDebug2(worker, "Maapi.Attached: from="+fromTh+" to="+toTh+" usid="+usid);
        } catch (Exception e) {
            throw new NedException("Internal ERROR: maapiAttach()", e);
        }
    }


    /**
     * Detach from Maapi
     * @param
     * @throws NedException
     */
    private void maapiDetach(NedWorker worker, int fromTh, int toTh) throws NedException {
        try {
            if (fromTh != -1) {
                maapi.detach(fromTh);
            }
            if (toTh != -1) {
                maapi.detach(toTh);
            }
            traceDebug2(worker, "Maapi.Detached: from="+fromTh+" to="+toTh);
        } catch (Exception e) {
            throw new NedException("Internal ERROR: maapiDetach(): "+e.getMessage(), e);
        }
    }


    /**
     * Check if path exists in CDB using Maapi
     * @param
     * @return
     * @throws NedException
     */
    protected boolean maapiExists(NedWorker worker, int th, String path) throws NedException {
        try {
            if (maapi.exists(th, path)) {
                traceVerbose(worker, "maapiExists("+path+") = true");
                return true;
            }
        } catch (Exception e) {
            throw new NedException("maapiExists("+path+") ERROR: " + e.getMessage(), e);
        }

        traceVerbose(worker, "maapiExists("+path+") = false");
        return false;
    }


    /**
     *
     * @param
     * @return
     */
    protected String maapiGetLeafString(int th, String path) {
        // Trim to absolute path
        int up;
        while ((up = path.indexOf("/../")) > 0) {
            int slash = path.lastIndexOf('/', up-1);
            path = path.substring(0, slash) + path.substring(up + 3);
        }
        // Get leaf
        try {
            if (maapi.exists(th, path)) {
                return ConfValue.getStringByValue(path, maapi.getElem(th, path));
            }
        } catch (Exception e) {
            // Ignore Exception
        }
        return null;
    }


    /**
     *
     * @param
     * @return
     */
    private boolean isTopExit(String line) {
        line = line.replace("\r", "");
        if (line.equals("exit")) {
            return true;
        }
        if (line.equals("!")) {
            return true;
        }
        return false;
    }


    /**
     *
     * @param
     * @return
     */
    private static String findLine(String buf, String search) {
        int i = buf.indexOf(search);
        if (i >= 0) {
            int nl = buf.indexOf('\n', i+1);
            if (nl >= 0) {
                return buf.substring(i,nl);
            } else {
                return buf.substring(i);
            }
        }
        return null;
    }


    /**
     *
     * @param
     * @throws Exception
     */
    private void expect_echo(String line, NedWorker worker)
            throws IOException, SSHSessionException {

        // Wait for echo, character by character due to escape sequence
        //traceVerbose(worker, "Waiting for echo of: '"+line+"'");
        if (trace) {
            session.setTracer(null);
        }
        for (int n = 0; n < line.length(); n++) {
            String c = line.substring(n, n+1);
            session.expect(new String[] { Pattern.quote(c) }, worker);
        }
        if (trace) {
            session.setTracer(worker);
        }
        traceVerbose(worker, line);
    }


    /**
     *
     * @param
     * @return
     * @throws Exception
     */
    protected boolean changeto_context(NedWorker worker, String context, boolean throwException)
        throws NedException, IOException, SSHSessionException {
        return changeto_context(session, worker, context, throwException);
    }


    /**
     *
     * @param
     * @return
     * @throws Exception
     */
    private boolean changeto_context(CliSession tsession, NedWorker worker, String context, boolean throwException)
        throws NedException, IOException, SSHSessionException {

        // Send changeto command and wait for echo
        String cmd = "changeto system";
        if (!"system".equals(context)) {
            cmd = "changeto context "+context;
        }
        String reply = print_line_exec(tsession, worker, cmd);
        if (isExecError(reply)) {
            if (throwException) {
                throw new NedException("Failed to changeto /"+context+" context");
            }
            traceInfo(worker, "ERROR - failed to changeto /"+ context+" context: " + reply);
            return false;
        }

        if (!context.equals("system") && !context.equals("admin")) {
            // Note: system and admin contexts got pager set at login
            print_line_exec(worker, "terminal pager 0");
        }

        return true;
    }


    /**
     * Send command to device, wait for echo and reply. Return output before prompt
     * @param
     * @return
     * @throws IOException, SSHSessionException
     */
    protected String print_line_exec(NedWorker worker, String line)
        throws IOException, SSHSessionException {
        return print_line_exec(this.session, worker, line);
    }

    private String print_line_exec(CliSession tsession, NedWorker worker, String line)
        throws IOException, SSHSessionException {

        // Check simulated command
        String res = simulateCommand(worker, line);
        if (res != null) {
            return res;
        }

        // Send command and wait for echo (using read-timeout)
        tsession.print(line + "\n");
        tsession.expect(new String[] { Pattern.quote(line) }, worker);

        // Return command output
        return tsession.expect(EXEC_PROMPT, worker);
    }

    protected String print_line_exec(NedWorker worker, String line, int timeout)
        throws IOException, SSHSessionException {

        // Check simulated command
        String res = simulateCommand(worker, line);
        if (res != null) {
            return res;
        }

        // Send command and wait for echo (using read-timeout)
        session.print(line + "\n");
        session.expect(new String[] { Pattern.quote(line) }, worker);

        // Reset timeout after echo in case expect() reset timeout or echo slow
        resetTimeout(worker, timeout, 0);

        // Return command output
        return session.expect(EXEC_PROMPT, timeout, worker);
    }

    protected String print_line_exec_slow(NedWorker worker, String line, int timeout)
        throws IOException, SSHSessionException {

        // Check simulated command
        String res = simulateCommand(worker, line);
        if (res != null) {
            return res;
        }

        // Send command and wait for echo
        if (writeSlowNewlineDelay > 0) {
            session.print(line);
            session.expect(new String[] { Pattern.quote(line) }, worker);
            sleep(worker, writeSlowNewlineDelay, " - write slow-newline-delay");
            traceVerbose(worker, "Sending newline");
            session.print("\n");
        } else {
            session.print(line + "\n");
            session.expect(new String[] { Pattern.quote(line) }, worker);
        }

        // Wait for device reply with prompt
        traceVerbose(worker, "Waiting for prompt");
        if (timeout > 0) {
            // Timeout specified
            resetTimeout(worker, timeout, 0);
            return session.expect(EXEC_PROMPT, timeout, worker);
        } else {
            // No specified timeout
            return session.expect(EXEC_PROMPT, worker);
        }
    }


    /**
     *
     * @param
     * @return
     */
    protected String simulateCommand(NedWorker worker, String line) {
        // ned-setting cisco-asa developer simulate-command *
        try {
            HashMap<String,String> map = new HashMap<>();
            String path = "developer/simulate-command{\""+line+"\"}/file";
            nedSettings.getMatching(map, path);
            if (map.size() > 0) {
                String filename = map.get(path);
                if (filename != null) {
                    String output = readFile(filename);
                    if (output != null) {
                        traceInfo(worker, "Simulating '"+line+"' output from '"+filename+"':\n"+output);
                        return output;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }


    /**
     *
     * @param
     * @return
     */
    private boolean isExecError(String res) {
        return res.contains("ERROR: ")
            || res.trim().startsWith("%Error ")
            || res.contains("Command not valid ");
    }


    /**
     * Send oper command to device
     * @param
     * @throws Exception
     */
    private void print_line_wait_oper(NedWorker worker, String line)
        throws NedException, IOException, SSHSessionException, ApplyException {
        print_line_wait_oper0(worker, line, this.readTimeout);
    }

    private void print_line_wait_oper_slow(NedWorker worker, String line, int timeout)
        throws NedException, IOException, SSHSessionException, ApplyException {
        print_line_wait_oper0(worker, line, timeout);
        setReadTimeout(worker);
    }

    private void print_line_wait_oper0(NedWorker worker, String line, int timeout)
        throws NedException, IOException, SSHSessionException, ApplyException {

        traceVerbose(worker, "SENDING_OPER: '"+line+"' (timeout = "+timeout+")");

        // Send command and wait for echo
        if (timeout > this.readTimeout && writeSlowNewlineDelay > 0) {
            session.print(line);
            session.expect(new String[] { Pattern.quote(line) }, worker);
            sleep(worker, writeSlowNewlineDelay, " - write slow-newline-delay");
            session.print("\n");
        } else {
            session.print(line + "\n");
            session.expect(new String[] { Pattern.quote(line) }, worker);
        }

        // Reset timeout
        resetTimeout(worker, timeout, 0);

        // Wait for prompt
        NedExpectResult res;
        try {
            res = session.expect(new String[] {
                    "Overwrite the previous NVRAM configuration\\?\\[confirm\\]", EXEC_PROMPT }, worker);
        } catch (Exception e) {
            throw new ApplyException(line, "oper read timeout", true, false);
        }

        if (res.getHit() == 0) {
            // Confirm question with "y" and wait for prompt again
            session.print("y");
            res = session.expect(new String[] {".*#"}, worker);
        }

        // Check for errors
        String[] lines = res.getText().split("\n|\r");
        for (int i = 0 ; i < lines.length ; i++) {
            if (lines[i].toLowerCase().contains("error")
                || lines[i].toLowerCase().contains("failed")) {
                throw new ApplyException(line, lines[i], true, false);
            }
        }
    }


    /**
     * Check if device
     * @return
     */
    protected String getModel() {
        if (devModel != null) {
            return devModel;
        }
        return this.asamodel;
    }


    /**
     *
     * @param
     * @return
     */
    private boolean isDevice() {
        return !isNetsim();
    }


    /**
     * Check if NETSIM
     * @return
     */
    public boolean isNetsim() {
        return getModel().contains("NETSIM");
    }
    public boolean isTrueNetsim() {
        return this.asamodel.contains("NETSIM");
    }


    /**
     *
     * @param
     * @return
     */
    protected boolean isAdminContext() {
        if (this.haveContext && contextName == null) {
            return true;
        }
        return false;
    }


    /**
     *
     * @param
     * @return
     */
    private boolean isSingleContext() {
        if (!this.haveContext) {
            return true;
        }
        if (contextName != null) {
            return true;
        }
        return false;
    }


    /**
     * Sleep milliseconds, reset timeout for long sleeps
     * @param
     */
    private void sleep(NedWorker worker, long milliseconds, String log) {
        if (milliseconds > 3000) {
            resetTimeout(worker, readTimeout + (int)milliseconds, 0);
        }
        if (log != null) {
            traceVerbose(worker, "Sleeping " + milliseconds + " milliseconds"+log);
        }
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            traceVerbose(worker, "sleep interrupted");
            Thread.currentThread().interrupt();
        }
        if (log != null) {
            traceVerbose(worker, "Woke up from sleep");
        }
    }


    /**
     * Strip all lines beginning with 'search' in 'res' config dump
     * @param
     * @return
     */
    private String stripLineAll(NedWorker worker, String res, String search, boolean contains) {
        StringBuilder buffer = new StringBuilder();
        String[] lines = res.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if ((!contains && lines[i].trim().startsWith(search))
                || (contains && lines[i].contains(search))) {
                traceInfo(worker, "transformed <= stripped "+stringQuote(lines[i]));
                continue;
            }
            buffer.append(lines[i]+"\n");
        }
        return buffer.toString();
    }


    /**
     * Read file from disk
     * @param
     * @return
     * @throws Exception
     */
    private String readFile(String file) throws IOException {
        BufferedReader reader = new BufferedReader(new java.io.FileReader(file));
        String line = null;
        StringBuilder sb = new StringBuilder();
        try {
            while ((line = reader.readLine()) != null) {
                sb.append(line);
                sb.append("\r\n");
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }

}
