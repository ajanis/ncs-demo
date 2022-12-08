package com.tailf.packages.ned.iosxr;

import java.util.EnumSet;

import com.tailf.packages.ned.nedcom.Schema;
import com.tailf.packages.ned.nedcom.NedComCliBase;
import com.tailf.packages.ned.nedcom.NedDiff;
import com.tailf.packages.ned.nedcom.MaapiUtils;
import com.tailf.packages.ned.nedcom.NedSettings;
import com.tailf.packages.ned.nedcom.NedMetaData;
import com.tailf.packages.ned.nedcom.NedSecretCliExt;
import com.tailf.packages.ned.nedcom.livestats.NedLiveStats;
import com.tailf.packages.ned.nedcom.livestats.NedLiveStatsException;
import com.tailf.packages.ned.nedcom.livestats.NedLiveStatsShowHandler;
import com.tailf.packages.ned.nedcom.NedCommonLib;
import com.tailf.packages.ned.nedcom.NedCommonLib.NedState;
import com.tailf.packages.ned.nedcom.NedCommonLib.PlatformInfo;
import com.tailf.packages.ned.nedcom.NedProgress;
import com.tailf.packages.ned.nedcom.NedMetaData.NedCapabilities;
import static com.tailf.packages.ned.nedcom.NedProgress.reportProgressStart;
import static com.tailf.packages.ned.nedcom.NedProgress.reportProgressStop;
import com.tailf.packages.ned.nedcom.ssh.NedSSHClient;

import static com.tailf.packages.ned.nedcom.NedString.getMatch;
import static com.tailf.packages.ned.nedcom.NedString.getMatches;
import static com.tailf.packages.ned.nedcom.NedString.fillGroups;
import static com.tailf.packages.ned.nedcom.NedString.stringQuote;
import static com.tailf.packages.ned.nedcom.NedString.stringDequote;
import static com.tailf.packages.ned.nedcom.NedString.passwordQuote;
import static com.tailf.packages.ned.nedcom.NedString.passwordDequote;
import static com.tailf.packages.ned.nedcom.NedString.linesToString;
import static com.tailf.packages.ned.nedcom.NedString.calculateMd5Sum;
import static com.tailf.packages.ned.nedcom.NedString.findString;
import static com.tailf.packages.ned.nedcom.NedString.matcherToString;

import java.io.File;
import java.io.FilenameFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.BufferedReader;
import java.text.StringCharacterIterator;
import java.text.CharacterIterator;
import java.security.NoSuchAlgorithmException;

import java.util.Scanner;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.Comparator;
import java.util.Collections;

import com.tailf.conf.Conf;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfValue;
import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfXMLParam;
import com.tailf.conf.ConfXMLParamValue;
import com.tailf.conf.ConfException;

import com.tailf.maapi.MaapiInputStream;
import com.tailf.maapi.MaapiConfigFlag;
import com.tailf.maapi.MaapiCrypto;
import com.tailf.maapi.MaapiCursor;
import com.tailf.maapi.MaapiException;

import com.tailf.ned.NedException;
import com.tailf.ned.NedExpectResult;

import com.tailf.ned.NedMux;
import com.tailf.ned.NedWorker;
import com.tailf.ned.CliSession;
import com.tailf.ned.SSHSessionException;


/**
 * Implements the cisco-iosxr CLI NED
 * @author lbang
 *
 */
@SuppressWarnings("deprecation")
public class IosxrNedCli extends NedComCliBase {

    // Constants
    private static final String PFX = "cisco-ios-xr:";

    private static final String UNKNOWN = "unknown";
    private static final String NSKEY = "__key__";

    // start of input, 1 character, > 0 non-# and ' ', one #, >= 0 ' ', eol
    private static final String PROMPT = "\\A[a-zA-Z0-9][^\\# ]+#[ ]?$";
    private static final String CONFIG_PROMPT = "\\A.*\\(.*\\)#[ ]?$";
    private static final String CMD_ERROR = "xyzERRORxyz";

    private static final int TIMEOUT_MOD = 100;

    // List used by the showPartial() handler.
    private static Pattern[] protectedPaths = new Pattern[] {
        Pattern.compile("policy-map \\S+( \\\\ class\\s+\\S+).*"),
        Pattern.compile("admin( \\\\.*)")
    };

    // NEDLIVESTATS prompts
    private static final Pattern[] NEDLIVESTATS_PROMPT = new Pattern[] {
        Pattern.compile("\\A.*\\(.*\\)#[ ]?$"),
        Pattern.compile("\\A[a-zA-Z0-9][^\\# ]+#[ ]?$")
    };

    private static final Pattern[] MOVE_TO_TOP_PATTERN = new Pattern[] {
        Pattern.compile("\\A.*\\((admin-)?config\\)#"),
        Pattern.compile("Invalid input detected at"),
        Pattern.compile("\\A.*\\(.*\\)#"),
        Pattern.compile(PROMPT)
    };

    private static final Pattern[] NOPRINT_LINE_WAIT_PATTERN = new Pattern[] {
        Pattern.compile("\\A.*Uncommitted changes found, commit them"),
        Pattern.compile("\\A.*\\([a-z-]+[^\\(\\)#]+\\)#"),
        Pattern.compile(PROMPT)
    };

    private static final Pattern[] ENTER_CONFIG_PATTERN = new Pattern[] {
        Pattern.compile("\\A\\S*\\((admin-)?config.*\\)#"),
        Pattern.compile("\\A.*running configuration is inconsistent with persistent configuration"),
        Pattern.compile("\\A\\S*#")
    };

    private static final Pattern[] EXIT_CONFIG_PATTERN = new Pattern[] {
        Pattern.compile("\\A.*Uncommitted changes found, commit them"),
        Pattern.compile("You are exiting after a 'commit confirm'"),
        Pattern.compile("Invalid input detected at "),
        Pattern.compile(PROMPT)
    };

    /**
     * Warnings, regular expressions. NOTE: Lowercase!
     */
    private static final String[] staticErrors = {
        "error",
        "aborted",
        "exceeded",
        "invalid",
        "incomplete",
        "duplicate name",
        "may not be configured",
        "should be in range",
        "is used by",
        "being used",
        "cannot be deleted",
        "bad mask",
        "failed"
    };

    // PROTECTED:
    protected boolean adminLogin = false; // logged into admin mode directly
    protected boolean inConfig = false; // NED in config mode
    protected boolean isDry = false;
    protected boolean resetTerminal = false;
    protected StringBuilder extInjectFirst;
    protected StringBuilder delayedCommit = new StringBuilder();
    protected String confPath;
    protected int snmpAllTraps = 0; // -1=delete, 1=created

    // Utility classes
    private MaapiCrypto mCrypto = null;
    private NedCommand nedCommand;

    // devices info
    private String iosversion = UNKNOWN;
    private String iosmodel = UNKNOWN;
    private String iosserial = UNKNOWN;
    private String deviceProfile = "null";

    // SUPPORT:
    private boolean supportCommitShowError = true;

    // NED-SETTINGS
    private ArrayList<String> dynamicWarning = new ArrayList<>();
    private ArrayList<String[]> injectCommand = new ArrayList<>();
    private ArrayList<String[]> autoPrompts = new ArrayList<>();
    private ArrayList<String[]> replaceConfig = new ArrayList<>();
    private String transIdMethod;
    private String commitMethod;
    private int commitConfirmedTimeout;
    private int commitConfirmedDelay;
    private String commitOverrideChanges;
    private String configMethod;
    private boolean showRunningStrictMode;
    private int chunkSize;
    private boolean includeCachedShowVersion;
    private boolean autoCSCtk60033Patch;
    private boolean autoCSCtk60033Patch2;
    private boolean autoAclDeletePatch;
    private boolean autoAaaTacacsPatch;
    private boolean autoSnmpServerCommunityPatch;
    private boolean apiEditRoutePolicy;
    private boolean apiSpList;
    private boolean apiClassMapMatchAGList;
    private boolean apiGroupModeled;
    private int apiSnmpServerEnableAllTraps;
    private String remoteConnection;
    private String readDeviceMethod;
    private String connSerialMethod;
    private String connAdminName;
    private String connAdminPassword;
    private String readDeviceFile;
    private boolean readStripComments;
    private boolean readAdminShowRun;
    private int writeSftpThreshold;
    private String writeDeviceFile;
    private int writeOobExclusiveRetries;
    private String revertMethod;
    private boolean preferPlatformSN;
    private String platformModelRegex;
    private boolean devTraceInputBytes;

    // States
    private long lastTimeout;
    private String commitCommand;
    private String syncFile = null;
    private String offlineData = null;
    private boolean showRaw = false;
    private String operPath;
    private String confRoot;
    protected String operRoot;

    // Cached data (cleared in clearDataCache)
    private String lastGetConfig = null;
    private String lastTransactionId = null;
    private int numCommit = 0;
    private int numAdminCommit = 0;

    // Debug
    private String failphase = "";
    private int applyDelay = 0;
    private int totalTraces;
    private int totalLines;
    private long totalTime;
    private int totalFailed;


    /*
     **************************************************************************
     * Constructors
     **************************************************************************
     */

    /**
     * NED cisco-iosxr constructor
     */
    public IosxrNedCli() {
        super();
    }


    /**
     * NED cisco-iosxr constructor
     * @param deviceId
     * @param mux
     * @param trace
     * @param worker
     */
    public IosxrNedCli(String deviceId, NedMux mux, boolean trace, NedWorker worker) throws Exception {
        super(deviceId, mux, trace, worker);
        this.operPath = "/ncs:devices/ncs:device{"+deviceId+"}/ncs:ned-settings/iosxr-op:cisco-iosxr-oper";
        this.confRoot = "/ncs:devices/ncs:device{"+deviceId+"}/config/cisco-ios-xr:";
        this.confPath = this.confRoot;
        this.operRoot = "/ncs:devices/ncs:device{"+deviceId+"}/ncs:ned-settings/iosxr-op:cisco-iosxr-oper";
    }


    /*
     **************************************************************************
     * setupParserContext
     **************************************************************************
     */

    /**
     * Override and init Schema.ParserContext with tailfned api info
     * @param
     */
    @Override
    protected void setupParserContext(Schema.ParserContext pctx) {
        if (pctx.parserDirection != Schema.ParserDirection.FROM_DEVICE) {
            return;
        }

        // FROM_DEVICE
        NedWorker worker = (NedWorker)pctx.externalContext;
        if (apiEditRoutePolicy) {
            traceVerbose(worker, "Adding /tailfned/api/edit-route-policy");
            pctx.addVirtualLeaf("/tailfned/api/edit-route-policy", "");
        }
        if (apiSpList) {
            traceVerbose(worker, "Adding /tailfned/api/service-policy-list");
            pctx.addVirtualLeaf("/tailfned/api/service-policy-list", "");
        }
        if (apiClassMapMatchAGList) {
            traceVerbose(worker, "Adding /tailfned/api/class-map-match-access-group-list");
            pctx.addVirtualLeaf("/tailfned/api/class-map-match-access-group-list", "");
        }
        if (apiGroupModeled) {
            traceVerbose(worker, "Adding /tailfned/api/group-modeled");
            pctx.addVirtualLeaf("/tailfned/api/group-modeled", "");
        }
        if (apiSnmpServerEnableAllTraps != 0) {
            traceVerbose(worker, "Adding /tailfned/api/snmp-server-enable-all-traps");
            pctx.addVirtualLeaf("/tailfned/api/snmp-server-enable-all-traps", "");
        }
    }


    /**
     * For turbo parser and showPartial()
     * @param
     */
    @Override
    protected void preParseNotify(NedWorker worker, Schema.ParserContext pctx, NedState state) {
        if (state == NedState.SHOW_PARTIAL || pctx.parserDirection == Schema.ParserDirection.TO_DEVICE) {
            traceVerbose(worker, "Adding to-transaction data-provider for ned-settings api");
            addTransactionDataProvider(pctx);
        }
    }


    /*
     **************************************************************************
     * nedSettingsDidChange
     **************************************************************************
     */

    /**
     * Called when ned-settings changed
     * @param
     * @throws Exception
     */
    @Override
    public void nedSettingsDidChange(NedWorker worker, Set<String> changedKeys, boolean isConnected) throws Exception {
        final long start = tick(0);
        traceInfo(worker, "BEGIN nedSettingsDidChange");

        // Cache ned-settings
        try {
            // connection
            preferPlatformSN = nedSettings.getBoolean("connection/prefer-platform-serial-number");
            platformModelRegex = nedSettings.getString("connection/platform-model-regex");
            connSerialMethod = nedSettings.getString("connection/serial-number-method");
            connAdminName = nedSettings.getString("connection/admin/name");
            connAdminPassword = nedSettings.getString("connection/admin/password");

            // proxy
            remoteConnection = nedSettings.getString("proxy/remote-connection");

            // read
            transIdMethod = nedSettings.getString("read/transaction-id-method");
            readDeviceMethod = nedSettings.getString("read/method");
            readDeviceFile = nedSettings.getString("read/file");
            readStripComments = nedSettings.getBoolean("read/strip-comments");
            showRunningStrictMode = nedSettings.getBoolean("read/show-running-strict-mode");
            readAdminShowRun = nedSettings.getBoolean("read/admin-show-running-config");

            // write
            commitMethod = nedSettings.getString("write/commit-method");
            String commitOptions = nedSettings.getString("write/commit-options");
            commitCommand = "commit " + commitOptions.trim();
            commitConfirmedTimeout = nedSettings.getInt("write/commit-confirmed-timeout");
            commitConfirmedDelay = nedSettings.getInt("write/commit-confirmed-delay");
            commitOverrideChanges = nedSettings.getString("write/commit-override-changes");
            revertMethod = nedSettings.getString("write/revert-method");
            configMethod = nedSettings.getString("write/config-method");
            chunkSize = nedSettings.getInt("write/number-of-lines-to-send-in-chunk");
            writeSftpThreshold = nedSettings.getInt("write/sftp-threshold");
            writeDeviceFile = nedSettings.getString("write/file");
            writeOobExclusiveRetries = nedSettings.getInt("write/oob-exclusive-retries");

            // api
            apiEditRoutePolicy = nedSettings.getBoolean("api/edit-route-policy");
            apiSpList = nedSettings.getBoolean("api/service-policy-list");
            apiClassMapMatchAGList = nedSettings.getBoolean("api/class-map-match-access-group-list");
            apiGroupModeled = nedSettings.getBoolean("api/group-modeled");
            apiSnmpServerEnableAllTraps = nedSettings.getInt("api/snmp-server-enable-all-traps");

            // auto
            autoCSCtk60033Patch = nedSettings.getBoolean("auto/CSCtk60033-patch");
            autoCSCtk60033Patch2 = nedSettings.getBoolean("auto/CSCtk60033-patch2");
            autoAclDeletePatch = nedSettings.getBoolean("auto/acl-delete-patch");
            autoAaaTacacsPatch = nedSettings.getBoolean("auto/aaa-tacacs-patch");
            autoSnmpServerCommunityPatch = nedSettings.getBoolean("auto/snmp-server-community-patch");

            // developer
            devTraceInputBytes = nedSettings.getBoolean("developer/trace-input-bytes");

            // deprecated
            includeCachedShowVersion = nedSettings.getBoolean("deprecated/cached-show-enable/version");

            //
            // Get read/replace-config
            //
            List<Map<String,String>> entries;
            entries = nedSettings.getListEntries("read/replace-config");
            for (Map<String,String> entry : entries) {
                String[] newEntry = new String[3];
                newEntry[0] = entry.get(NSKEY); // "id"
                newEntry[1] = entry.get("regexp");
                newEntry[2] = entry.get("replacement");
                String buf = "read/replace-config "+newEntry[0];
                buf += " regexp "+stringQuote(newEntry[1]);
                if (newEntry[1] == null) {
                    throw new NedException("ned-settings: read/replace-config "+newEntry[0]+" missing regexp");
                }
                if (newEntry[2] != null) {
                    buf += " to "+stringQuote(newEntry[2]);
                } else {
                    newEntry[2] = "";
                    buf += " filtered";
                }
                traceVerbose(worker, buf);
                replaceConfig.add(newEntry);
            }

            //
            // Get config warnings
            //
            entries = nedSettings.getListEntries("write/config-warning");
            for (Map<String,String> entry : entries) {
                String key = entry.get(NSKEY);
                traceVerbose(worker, "write/config-warning "+key);
                dynamicWarning.add(stringDequote(key));
            }

            //
            // Get inject command(s)
            //
            entries = nedSettings.getListEntries("write/inject-command");
            for (Map<String,String> entry : entries) {
                String[] newEntry = new String[4];
                newEntry[0] = entry.get(NSKEY); // "id"
                newEntry[1] = entry.get("config");
                newEntry[2] = entry.get("command");
                newEntry[3] = entry.get("where");
                if (newEntry[1] == null || newEntry[2] == null || newEntry[3] == null) {
                    throw new NedException("write/inject-command "+newEntry[0]
                                           +" missing config, command or <where>, check your ned-settings");
                }
                traceVerbose(worker, "inject-command "+newEntry[0]
                             +" cfg "+stringQuote(newEntry[1])
                             +" cmd "+stringQuote(newEntry[2])
                             +" "+newEntry[3]);
                injectCommand.add(newEntry);
            }

            //
            // Get auto-prompts
            //
            entries = nedSettings.getListEntries("live-status/auto-prompts");
            for (Map<String,String> entry : entries) {
                String[] newEntry = new String[3];
                newEntry[0] = entry.get(NSKEY); // "id"
                newEntry[1] = entry.get("question");
                newEntry[2] = entry.get("answer");
                traceVerbose(worker, "cisco-iosxr-auto-prompts "+newEntry[0]
                             + " q \"" +newEntry[1]+"\""
                             + " a \"" +newEntry[2]+"\"");
                autoPrompts.add(newEntry);
            }
        } catch (Exception e) {
            throw new NedException("Failed to read ned-settings :: "+e.getMessage(), e);
        }

        if (logVerbose) {
            ch.ethz.ssh2.log.Logger.enabled = true;
        }

        traceInfo(worker, "DONE nedSettingsDidChange "+tickToString(start));
    }


    /**
     * Setup NedDiff
     * @param
     * @throws NedException
     */
    private void setupNedDiff(NedWorker worker) {

        // Initialize NedDiff
        nedDiff = new NedDiff(this, devTraceEnable);

        // Add user rules from ned-settings 'write config-dependency' list
        nedDiff.addNedSettings(worker, nedSettings);
    }


    /*
     **************************************************************************
     * updateNedCapabilities
     **************************************************************************
     */

    /**
     * Update NED capas regarding reverse diff and path format for partial show
     * @param
     */
    public NedCapabilities updateNedCapabilities(NedCapabilities capabilities) {
        final String defaultFormat = "cmd-path-modes-only";
        final int nsoRunningVersion = getNsoRunningVersion();
        final String method = nedSettings.getString("read/partial-show-method");
        if ("cmd-path-full".equals(method)) {
            capabilities.partialFormat = method;
        } else if ("filter-mode".equals(method)) {
            /*
             * On NETSIM and if explicitly configured, use the show-partial filter-mode.
             * The 'key-path' feature does not work properly on NSO versions < 4.4.3
             */
            capabilities.partialFormat = (nsoRunningVersion >= 0x6040300) ? "key-path" : defaultFormat;
        } else {
            capabilities.partialFormat = defaultFormat;
        }
        return capabilities;
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
        final long start = tick(0);
        traceInfo(worker, "BEGIN PROBE");

        //
        // Logged in, set terminal settings and check device type
        //
        int th = -1;
        try {
            resetTimeout(worker, this.connectTimeout, 0);

            // Set terminal settings
            print_line_exec(worker, "terminal length 0");
            print_line_exec(worker, "terminal width 0");

            // Show version
            String version = print_line_exec(worker, "show version brief");
            if (isExecError(version)) {
                version = print_line_exec(worker, "show version");
            }
            version = version.replace("\r", "");

            /* Verify we connected to a XR device */
            if (version.contains("Cisco IOS XR Admin Software")) {
                traceInfo(worker, "connecting to admin mode");
                adminLogin = true;
            } else if (!version.contains("Cisco IOS XR Software")) {
                throw new NedException("Unknown device :: " + version);
            }

            setUserSession(worker);
            th = maapi.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);

            // Read device-profile (for debug purposes)
            this.deviceProfile = getDeviceProfile(th);

            //
            // NETSIM
            //
            if (version.contains("NETSIM")) {
                this.iosmodel = "NETSIM";
                this.iosversion = "cisco-iosxr-" + NedMetaData.nedVersion;
                this.iosserial = this.device_id;

                // Show CONFD & NED version used by NETSIM in ned trace
                print_line_exec(worker, "show confd-state version");
                print_line_exec(worker, "show confd-state loaded-data-models data-model tailf-ned-cisco-ios-xr");

                // Set NETSIM data and defaults
                transIdMethod = "config-hash"; // override
                readDeviceMethod = "show running-config";
                writeSftpThreshold = 2147483647;
                apiSnmpServerEnableAllTraps = 0;
            }

            //
            // DEVICE
            //
            else {
                // Get version
                Pattern p = Pattern.compile("Cisco IOS XR(?: Admin)? Software, Version (\\S+)");
                Matcher m = p.matcher(version);
                if (m.find()) {
                    iosversion = m.group(1);
                    int n;
                    if ((n = iosversion.indexOf('[')) > 0) {
                        iosversion = iosversion.substring(0,n);
                    }
                }

                // Get model
                if (platformModelRegex != null) {
                    p = Pattern.compile(platformModelRegex);
                    m = p.matcher(version);
                    if (m.find()) {
                        this.iosmodel = m.group(1);
                    } else {
                        throw new NedException("No match for platform-model-regex = "+stringQuote(platformModelRegex));
                    }
                } else {
                    p = Pattern.compile("\ncisco (.+?) (?:Series |\\().*");
                    m = p.matcher(version);
                    if (m.find()) {
                        this.iosmodel = m.group(1);
                    } else {
                        p = Pattern.compile("\n(NCS.+)");
                        m = p.matcher(version);
                        if (m.find()) {
                            this.iosmodel = m.group(1);
                        }
                    }
                }

                // Get serial-number
                iosserial = getSerial(worker, th, version);
                if (iosserial == null) {
                    traceInfo(worker, "WARNING: failed to retrieve serial-number");
                    iosserial = UNKNOWN;
                }

                // Include active packages in the trace
                if (logVerbose) {
                    print_line_exec(worker, "show install active summary");
                }
            }

        } catch (Exception e) {
            throw new NedException("Failed to setup NED :: "+e.getMessage(), e);
        } finally {
            if (th != -1) {
                maapi.finishTrans(th);
            }
        }

        traceInfo(worker, "DONE PROBE "+tickToString(start));
        return new PlatformInfo("ios-xr", iosversion, iosmodel, iosserial);
    }


    /**
     * Get device profile from CDB
     * @param
     * @return device profile name or null if error or not found
     */
    private String getDeviceProfile(int th) {
        try {
            String p = "/ncs:devices/device{"+this.device_id+"}/device-profile";
            if (maapi.exists(th, p)) {
                return ConfValue.getStringByValue(p, maapi.getElem(th, p));
            }
        } catch (Exception ignore) {
            // Ignore Exception
        }
        return "null";
    }


    /**
     * Get serial number from device
     * @param
     * @return Configuration
     * @throws Exception
     */
    private String getSerial(NedWorker worker, int th, String version)
        throws IOException, SSHSessionException, ConfException {
        String serial = null;

        // ned-settings cisco-iosxr connection prefer-platform-serial-number
        if (preferPlatformSN) {
            long perf = tick(0);
            serial = getPlatformData(th, "serial-number");
            if (serial != null && !UNKNOWN.equals(serial)) {
                traceInfo(worker, "devices device platform serial-number = "
                          +serial+" "+tickToString(perf));
                return serial;
            }
        }

        // Auto-update of default method
        if ("auto".equals(connSerialMethod)) {
            // NOTE: Seems 'show inventory' is faster on CRS/NCS (ncs6k), but slower on ASRs
            if (version.contains("cisco CRS") || version.contains("cisco NCS")) {
                connSerialMethod = "prefer-inventory";
            } else {
                connSerialMethod = "prefer-diag";
            }
        }

        // ned-settings cisco-iosxr connection serial-number-method
        if ("prefer-diag".equals(connSerialMethod)) {
            serial = getSerialDiag(worker);
            if (serial == null) {
                serial = getSerialInventory(worker);
            }
        } else if ("prefer-inventory".equals(connSerialMethod)) {
            serial = getSerialInventory(worker);
            if (serial == null) {
                serial = getSerialDiag(worker);
            }
        } else if ("inventory".equals(connSerialMethod)) {
            serial = getSerialInventory(worker);
        } else if ("diag".equals(connSerialMethod)) {
            serial = getSerialDiag(worker);
        }

        return serial;
    }


    /**
     * Get serial number using 'show diag'
     * @param
     * @return Configuration
     * @throws Exception
     */
    private String getSerialDiag(NedWorker worker) throws IOException, SSHSessionException {
        final long perf = tick(0);
        String serial = print_line_exec(worker, "show diag | include S/N");
        if ((serial = getMatch(serial, "S/N\\s*(?:[:])?\\s+(\\S+)")) != null) {
            traceInfo(worker, "show diag serial-number = "+serial+" "+tickToString(perf));
        } else {
            traceInfo(worker, "Failed to retrieve serial-number using 'show diag' "+tickToString(perf));
        }
        return serial;
    }


    /**
     * Get serial number using 'show inventory'
     * @param
     * @return Configuration
     * @throws Exception
     */
    private String getSerialInventory(NedWorker worker) throws IOException, SSHSessionException {
        final long perf = tick(0);
        String serial = print_line_exec(worker, "show inventory | include SN");
        if ((serial = getMatch(serial, "SN[:]\\s+(\\S+)")) != null) {
            traceInfo(worker, "show inventory serial-number = "+serial+" "+tickToString(perf));
        } else {
            traceInfo(worker, "Failed to retrieve serial-number using 'show inventory' "+tickToString(perf));
        }
        return serial;
    }


    /*
     **************************************************************************
     * connectDevice
     **************************************************************************
     */

    @Override
    public void connectDevice(NedWorker worker) throws Exception {
        traceInfo(worker, "BEGIN CONNECT-DEVICE");
        connectorConnectDevice(worker);
        traceInfo(worker, "DONE CONNECT-DEVICE");
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
    protected void setupInstance(NedWorker worker, PlatformInfo platformInfo) throws Exception {
        final long start = tick(0);
        traceInfo(worker, "BEGIN SETUP");

        if (this.writeTimeout < this.readTimeout) {
            traceInfo(worker, "WARNING: write-timeout too low, reset to read-timeout value");
            this.writeTimeout = this.readTimeout; // API CHANGE helper
        }

        this.iosmodel = platformInfo.model;
        this.iosversion = platformInfo.version;
        this.iosserial = platformInfo.serial;

        // device-profile
        traceInfo(worker, "device-profile = " + deviceProfile);

        // Create utility classes used by the NED
        secrets = new NedSecretCliExt(this);
        secrets.setDequoteOutput(true);
        secrets.setDebug(devTraceEnable);
        mCrypto = new MaapiCrypto(maapi);

        // NedCommand default auto-prompts:
        String[][] defaultAutoPrompts = new String[][] {
            { "([!]{20}|[C]{20}|[.]{20})", "<timeout>" },
            { "\\[OK\\]", null },
            { "\\[Done\\]", null },
            { "timeout is \\d+ seconds(?:, send interval is \\d+ msec)?:", null },  // ping
            { "Suggested steps to resolve this:", null }, // admin install
            { "Info: .*", null }, // (admin) install
            { "Warning: .*", null }, // (admin) install
            { "detected the 'warning' condition '.+?'", null },
            { ":\\s*$", "<prompt>" },
            { "\\][\\?]?\\s*$", "<prompt>" }
        };
        nedCommand = new NedCommand(this, "cisco-ios-xr-stats", "cisco-ios-xr", PROMPT, CONFIG_PROMPT,
                                    " Invalid input detected at ", defaultAutoPrompts);

        //
        // Only setup liveStats and SFTP for connected devices
        //
        if (session != null) {

            // Setup custom show handler
            nedLiveStats.setupCustomShowHandler(new ShowHandler(this, session, NEDLIVESTATS_PROMPT));

            // Make NedLiveStats aware of the ietf-interface and ietf-ip modules.
            nedLiveStats.installParserInfo("if:interfaces-state/interface",
                                           "{'show':'show interfaces',"+
                                           "'template':'if:interfaces-state_interface.gili',"+
                                           "'show-entry':{'cmd':'show interfaces %s',"+
                                           "'template':'if:interfaces-state_interface.gili',"+
                                           "'trim-top-node':true,'run-after-show':false}}");

            nedLiveStats.installParserInfo("if:interfaces-state/if:interface/ip:ipv4/ip:address",
                              "{'show':{'cmd':'show run interface %s | include ipv4 address','arg':['../../name']},"+
                              "'template':'if:interfaces-state_interface_ip:ipv4_address.gili'}");

            nedLiveStats.installParserInfo("if:interfaces-state/if:interface/ip:ipv6/ip:address",
                              "{'show':{'cmd':'show run interface %s | include ipv6 address','arg':['../../name']},"+
                              "'template':'if:interfaces-state_interface_ip:ipv6_address.gili'}");

            // SFTP download, create directory if not there
            if ("sftp-transfer".equals(readDeviceMethod) && readDeviceFile != null) {
                String path = getMatch(readDeviceFile, "(\\S+)/\\S+");
                if (path != null) {
                    traceInfo(worker, "sftp-transfer checking "+path+" directory");
                    String reply = print_line_exec(worker, "dir "+path);
                    if (reply.contains("No such file or directory")) {
                        traceInfo(worker, "SFTP creating download directory: "+path);
                        reply = nedCommand.runCommand(worker, "mkdir " + path + " | prompts ENTER");
                        if (!reply.contains("Created dir " + path)) {
                            traceInfo(worker, "sftp-transfer ERROR in creating 'read file' directory: "+ reply);
                            traceInfo(worker, "Disabling sftp-transfer");
                            readDeviceMethod = "show running-config";
                        }
                    }
                }
            }
        }

        // Set tailfned api from ned-setting
        if (isNetsim()) {
            StringBuilder sb = new StringBuilder();
            if (apiSpList) {
                traceInfo(worker, "Configuring tailfned api service-policy-list from ned-setting");
                sb.append("tailfned api service-policy-list\n");
            }
            if (apiClassMapMatchAGList) {
                traceInfo(worker, "Configuring tailfned api class-map-match-access-group-list from ned-setting");
                sb.append("tailfned api class-map-match-access-group-list\n");
            }
            if (apiGroupModeled) {
                traceInfo(worker, "Configuring tailfned api group-modeled from ned-setting");
                sb.append("tailfned api group-modeled\n");
            }
            if (sb.length() > 0) {
                nedCommand.commitConfig(worker, sb.toString());
            }
        }

        traceInfo(worker, "DONE SETUP "+tickToString(start));
    }


    /**
     * Get data from devices device platform
     * @param
     * @return Value or "unknown
     */
    protected String getPlatformData(int th, String leaf) throws IOException, ConfException {

        // First try devices device platform
        String p = "/ncs:devices/device{"+this.device_id+"}/platform/" + leaf;
        try {
            if (maapi.exists(th, p)) {
                return ConfValue.getStringByValue(p, maapi.getElem(th, p));
            }
        } catch (MaapiException ignore) {
            // Ignore Exception
        }

        // Second try config cached-show version
        if (includeCachedShowVersion) {
            p = confRoot + "cached-show/version/" + leaf;
            try {
                if (maapi.exists(th, p)) {
                    return ConfValue.getStringByValue(p, maapi.getElem(th, p));
                }
            } catch (MaapiException ignore) {
                // Ignore Exception
            }
        }

        return UNKNOWN;
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

            // ned-setting cisco-iosxr developer simulate-command *
            String simulated = simulateCommand(worker, cmd);
            if (simulated != null) {
                traceInfo(worker, "ShowHandler: Simulated output for '"+cmd+"'");
                return simulated;
            }

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

            // Send show command and wait for reply
            setReadTimeout(worker);
            session.println(cmd);
            session.expect(Pattern.quote(cmd), worker);
            NedExpectResult res = session.expect(prompts, worker);
            String dump = res.getText();

            // Dirty patch for "if:interfaces-state/if:interface/ip:ipv4/ip:address" and ipv4 netmask-format bit-count
            if (cmd.startsWith("show run interface") && cmd.endsWith("include ipv4 address") && dump.contains("/")) {
                Pattern p = Pattern.compile("ipv4 address (\\S+)[/](\\d+)");
                Matcher m = p.matcher(dump);
                if (m.find()) {
                    long bits = 0xffffffff ^ (1 << 32 - Integer.parseInt(m.group(2))) - 1;
                    final String mask = String.format("%d.%d.%d.%d",
                                                      (bits & 0x0000000000ff000000L) >> 24,
                                                      (bits & 0x0000000000ff0000) >> 16,
                                                      (bits & 0x0000000000ff00) >> 8, bits & 0xff);
                    dump = dump.replace(m.group(0), "ipv4 address "+m.group(1)+" "+mask);
                }
            }

            return dump;
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
        final long start = tick(0);
        if (session != null && trace) {
            session.setTracer(worker);
        }

        // Only respond to the first toptag
        if (!"interface".equals(toptag)) {
            worker.showCliResponse("");
            return;
        }

        traceInfo(worker, "BEGIN SHOW");

        // Clear cached data
        clearDataCache();

        // Get config from device
        String res = getConfig(worker, true);

        // Pre-calculate transaction-id
        this.lastTransactionId = doGetTransId(worker, res);
        traceInfo(worker, "SHOW TRANS_ID = "+this.lastTransactionId);

        // Auto-adjust config
        if (autoSnmpServerCommunityPatch) {
            setUserSession(worker);
            int th = maapi.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);
            try {
                String[] lines = res.split("\n");
                StringBuilder sb = new StringBuilder();
                for (int n = 0; n < lines.length; n++) {
                    String line = lines[n];
                    String match;
                    if (line.startsWith("snmp-server community") && line.contains("IPv4")
                        && (match = getMatch(line, "snmp-server community (\\S+).* R[OW] IPv4")) != null) {
                        final String path = this.confRoot + "snmp-server/community{"+match+"}";
                        if (maapiExists(worker,th,path) && !maapiExists(worker,th,path+"/access-list-type")) {
                            traceVerbose(worker, "transformed <= stripped IPv4 in '"+line+"'");
                            line = line.replace(" IPv4", "");
                        }
                    }
                    sb.append(line+"\n");
                }
                res = sb.toString();
            } finally {
                maapi.finishTrans(th);
            }
        }

        // cisco-iosxr extended-parser
        try {
            if (this.turboParserEnable) {
                traceInfo(worker, "Parsing config using turbo-mode");
                if (parseAndLoadXMLConfigStream(maapi, worker, schema, res)) {
                    res = ""; // Turbo-parser succeeded, clear config to bypass CLI
                }
            } else {
                traceInfo(worker, "Parsing config using robust-mode");
                res = filterConfig(res, schema, maapi, worker, null, false).toString();
                traceDev(worker, "SHOW-ROBUST-BUF=\n+++ show robust begin\n"+res+"\n+++ show robust end");
            }
        } catch (Exception e) {
            logError(worker, "extended-parser "+nedSettings.getString(NedSettings.EXTENDED_PARSER)
                     +" exception ERROR: ", e);
            this.turboParserEnable = false;
        }

        // New NSO feature, set provisional transaction-id in show()
        if (nedSettings.getBoolean("read/transaction-id-provisional")) {
            NedCommonLib.setProvisionalTransId(worker, this.lastTransactionId);
        }

        traceInfo(worker, "DONE SHOW "+tickToString(start));
        worker.showCliResponse(res);
    }


    /**
     * Get configuration from device
     * @param
     * @return Configuration
     * @throws Exception
     */
    private String getConfig(NedWorker worker, boolean convert) throws Exception {
        NedProgress.Progress progress = reportProgressStart(this, NedProgress.READ_CONFIG);
        try {
            // Reset timeout and get current time
            final long start = setReadTimeout(worker);

            //
            // Get config from device
            //
            String res = "";

            // showOffline
            if (offlineData != null) {
                traceInfo(worker, "Reading offline config...");
                res = "\n" + insertCarriageReturn(offlineData);
                traceInfo(worker, res);
            }

            // devices device <dev> live-status exec any sync-from-file <file>
            else if (convert && syncFile != null) {
                traceInfo(worker, "Reading sync-from-file config...");
                res = print_line_exec(worker, "file show " + syncFile);
                if (res.contains("Error: failed to open file")) {
                    throw new NedException("failed to sync from file " + syncFile);
                }
            }

            // sftp-transfer
            else if (!adminLogin && "sftp-transfer".equals(readDeviceMethod)) {
                traceInfo(worker, "Reading SFTP config...");
                if (remoteConnection != null) {
                    throw new NedException("sftp-transfer is not supported with proxy mode");
                }
                if (readDeviceFile == null) {
                    throw new NedException("No SFTP file name configured");
                }
                res = sftpGetConfig(worker);
            }

            // show running-config
            else if (!adminLogin) {
                traceInfo(worker, "Reading running config...");
                String cmd = readDeviceMethod;
                if (inConfig) {
                    cmd = "do " + cmd;
                }
                res = print_line_exec(worker, cmd);
            }
            setReadTimeout(worker);

            //
            // Trim config
            //
            res = trimInput(res);

            //
            // Prepend admin config
            //
            if (isDevice() && session != null && readAdminShowRun) {
                res = getAdminConfig(worker, true) + res;
            }

            //
            // Transform config
            //
            res = modifyInput(worker, res);

            //
            // Done
            //
            traceInfo(worker, "Reading config done "+tickToString(start));
            reportProgressStop(progress);
            return res;

        } catch (Exception e) {
            reportProgressStop(progress, NedProgress.ERROR);
            throw e;

        } finally {
            this.syncFile = null;
        }
    }


    /**
     * Insert missing carriage return, used by offline data
     * @param
     * @return
     */
    private String insertCarriageReturn(String res) {
        String[] lines = res.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int n = 0; n < lines.length; n++) {
            if (lines[n].endsWith("\r")) {
                sb.append(lines[n]+"\n");
            } else {
                sb.append(lines[n]+"\r\n");
            }
        }
        return sb.toString();
    }


    /**
     * Trim input config from show running-config
     * @param
     * @return
     */
    private String trimInput(String res) {

        if (res.trim().isEmpty()) {
            return res;
        }

        // Strip everything before:
        int i = res.indexOf("Building configuration...");
        int nl;
        if (i >= 0) {
            nl = res.indexOf('\n', i);
            if (nl > 0) {
                res = res.substring(nl+1);
            }
        }
        i = res.indexOf("!! Last configuration change");
        if (i >= 0) {
            nl = res.indexOf('\n', i);
            if (nl > 0) {
                res = res.substring(nl+1);
            }
        }
        i = res.indexOf("No entries found.");
        if (i >= 0) {
            nl = res.indexOf('\n', i);
            if (nl > 0) {
                res = res.substring(nl+1);
            }
        }

        // Strip everything after 'end'
        i = res.lastIndexOf("\nend");
        if (i >= 0) {
            res = res.substring(0,i);
        }

        // Strip timestamp
        res = res.trim() + "\r\n";
        if (res.startsWith("Mon ") || res.startsWith("Tue ") || res.startsWith("Wed ") || res.startsWith("Thu ")
            || res.startsWith("Fri ") || res.startsWith("Sat ") || res.startsWith("Sun ")) {
            nl = res.indexOf('\n');
            if (nl > 0) {
                res = res.substring(nl+1);
            }
        }

        return res;
    }


    /**
     * Get and transform admin config
     * @param
     * @return
     * @throws Exception
     */
    private String getAdminConfig(NedWorker worker, boolean enterAdmin)
        throws NedException, IOException, SSHSessionException {

        traceInfo(worker, "Reading admin config");

        if (inConfig) {
            throw new NedException("getAdminConfig() :: Internal ERROR: called in config mode");
        }

        // Enter admin mode
        if (enterAdmin && !enterAdmin(worker)) {
            return "";
        }

        // Show running-config in admin mode
        String res = "";
        try {
            res = print_line_exec(worker, "show running-config");
            if (isExecError(res)) {
                throw new NedException("Internal ERROR: failed to run 'show running-config' in admin mode");
            }
        } finally {
            if (enterAdmin) {
                exitAdmin(worker);
            }
        }

        // Trim beginning and end, may result in empty buf
        res = trimInput(res);
        if (res.trim().isEmpty()) {
            traceVerbose(worker, "SHOW_ADMIN: empty");
            return "";
        }

        // Trim and wrap in admin mode context
        String[] lines = res.split("\n");
        StringBuilder sb = new StringBuilder();
        sb.append("\nadmin\n");
        int next;
        String toptag = "";
        for (int n = 0; n < lines.length; n = next) {
            String line = lines[n];
            String trimmed = line.trim();
            next = n + 1;

            // Trim comments: '!! ' or '! '
            if (line.startsWith("!! ") || line.startsWith("! ")) {
                continue;
            }

            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = trimmed; // remove \r
            }
            String input = null;

            //
            // interface * / ipv4 address
            //
            if (toptag.startsWith("interface ") && trimmed.startsWith("ipv4 address ")) {
                input = line.replaceAll("ipv4 address ([0-9.]+)/(\\d+)", "ipv4 address $1 /$2");
            }

            // aaa authentication groups group * / users
            else if (toptag.startsWith("aaa authentication groups group ") && trimmed.startsWith("users ")) {
                input = line.replace("\"", ""); // Strip quotes around names
            }

            // unused
            else if (line.startsWith("TRIM-THIS-MODE-CONFIG")) {
                // Trim unsupported config to reduce trace spam
                while (next + 1 < lines.length &&
                       (lines[next].charAt(0) == ' ' || lines[next].trim().equals("!"))) {
                    next++;
                }
                continue;
            }

            // Add to admin dump
            if (input == null) {
                sb.append(" "+lines[n]+"\n");
            } else if (!input.isEmpty()) {
                sb.append(" "+input+"\n");
            }
        }

        // Return admin config
        sb.append("exit-admin-config\n");
        res = sb.toString();
        traceVerbose(worker, "SHOW-ADMIN-BUF=\n+++ show admin begin\n"+res+"\n+++ show admin end");
        return res;
    }


    /**
     * Connect with SFTP
     * @param
     * @return
     * @throws NedException
     */
    private NedSSHClient sftpConnect(NedWorker worker) throws NedException {
        traceInfo(worker, "SFTP connecting to " + ip.getHostAddress()+":"+port);
        try {
            NedSSHClient client = NedSSHClient.clone(getNedSSHClient());
            client.connect(connectTimeout, 0);
            client.authenticate(null, ruser, pass);
            traceInfo(worker, "SFTP connected");
            return client;

        } catch (Exception e) {
            throw new NedException("SFTP failed (check device SSH config or disable sftp in ned-settings) :: "
                                   + e.getMessage());
        }
    }


    /**
     * Get running-config from device using SFTP
     * @param
     * @return
     * @throws Exception
     */
    private String sftpGetConfig(NedWorker worker) throws Exception {

        // Copy over running-config to file
        traceInfo(worker, "SFTP copying running-config to file: " + readDeviceFile);
        String cmd = "copy running-config " + readDeviceFile + " | prompts ENTER yes";
        String reply = nedCommand.runCommand(worker, cmd);
        if (reply.startsWith(CMD_ERROR)) {
            throw new NedException("sftp-transfer ERROR: "+reply.replace(CMD_ERROR, ""));
        }
        if (reply.contains("No such file or directory")) {
            throw new NedException("sftp-transfer ERROR copying running-config to file, check 'read file' ned-setting");
        }

        // Get running-config file using SFTP
        NedSSHClient client = null;
        String config = "";
        try {
            // Connect using SSH
            client = sftpConnect(worker);
            NedSSHClient.SecureFileTransfer sftp = client.createSFTP();
            traceInfo(worker, "SFTP downloading " + readDeviceFile);
            config = sftp.get(readDeviceFile);
            traceInfo(worker, "SFTP downloaded "+config.length()+" bytes");
        } finally {
            if (client != null) {
                client.close();
            }
        }

        // Delete the temporary running-config copy (ignore errors)
        traceInfo(worker, "SFTP deleting running-config copy: " + readDeviceFile);
        print_line_exec(worker, "delete /noprompt " + readDeviceFile);

        // Done
        config = config.replace("\n", "\r\n"); // to match terminal output
        traceVerbose(worker, "SFTP-BUF=\n+++ sftp show begin\n"+config+"\n+++ sftp show end");
        return config;
    }


    /**
     * Modify input
     * @param
     * @return
     * @throws NedException
     */
    private String modifyInput(NedWorker worker, String res) throws ConfException, IOException, NedException {
        final long start = tick(0);
        this.confPath = this.confRoot;

        traceInfo(worker, "Transforming input config...");
        NedProgress.Progress progress = reportProgressStart(this, NedProgress.TRANSFORM_IN);

        try {
            // Single string group:
            if (!apiGroupModeled) {
                res = modifyInputGroup(worker, res, "");
            }

            // Detailed groups:
            else {
                // Split into group(s) + main config
                List<String> parts = splitConfigInGroups(res, true);
                StringBuilder sb = new StringBuilder();
                for (String dump : parts) {
                    dump = dump.trim() + "\n";

                    // main config
                    if (!dump.startsWith("group ")) {
                        sb.append(modifyInputGroup(worker, dump, ""));
                        continue;
                    }

                    // Group config
                    String[] lines = dump.split("\n");
                    final String group = lines[0].substring(6).trim();
                    this.confPath = this.confRoot + "group{"+group+"}";

                    // Shift left 1 to look like top
                    StringBuilder sbc = new StringBuilder();
                    for (int n = 1; n < lines.length; n++) {
                        if ("end-group".equals(lines[n].trim())) {
                            break;
                        }
                        sbc.append(lines[n].substring(1)+"\n");
                    }

                    // Modify group input config
                    dump = modifyInputGroup(worker, sbc.toString(), group);

                    // Add back group header, shift right 1 and add group footer
                    sb.append(lines[0]+"\n");
                    lines = dump.split("\n");
                    for (int n = 0; n < lines.length; n++) {
                        sb.append(" "+lines[n]+"\n");
                    }
                    sb.append(" end-group\n!\n");
                }
                res = sb.toString();
            }
        } catch (Exception e) {
            reportProgressStop(progress, NedProgress.ERROR);
            throw e;
        }

        traceInfo(worker, "Transforming input config done "+tickToString(start));
        reportProgressStop(progress);
        return res;
    }


    /**
     * Modify input group (or top)
     * @param
     * @return
     * @throws Exception
     */
    private String modifyInputGroup(NedWorker worker, String res, String groupName)
        throws ConfException, IOException, NedException {

        //
        // Inject config (main config only)
        //
        if (groupName.isEmpty()) {
            res = injectInput(worker, res);
        }

        //
        // Assemble banner into a single quoted string with start and end marker
        //
        final boolean isDevice = isDevice() || syncFile != null || offlineData != null;
        if (isDevice) {
            traceVerbose(worker, "quoting banners");
            Pattern p = Pattern.compile("\nbanner (\\S+) (\\S)(.*?)\\2[\\S ]*?\r", Pattern.DOTALL);
            Matcher m = p.matcher(res);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String name = m.group(1);
                String marker = passwordQuote(m.group(2));
                String message = stringQuote(m.group(3));
                traceVerbose(worker, "transformed <= quoted banner "+name);
                m.appendReplacement(sb, Matcher.quoteReplacement("\nbanner "+name+" "+marker+" "+message+" "+marker));
            }
            m.appendTail(sb);
            res = sb.toString();
        }

        //
        // NETSIM and DEVICE
        //
        traceVerbose(worker, "quoting strings");
        int i;
        int n;
        String match;
        String toptag = "";
        String[] lines = res.split("\n");
        StringBuilder sbin = new StringBuilder();
        for (n = 0; n < lines.length; n++) {
            String line = lines[n];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = trimmed; // remove \r
            }
            String input = null;

            //
            // description
            //
            if ((i = line.indexOf(" description ")) >= 0) {
                if (toptag.startsWith("l2vpn")
                    || toptag.startsWith("router static")
                    || toptag.startsWith("group ")
                    || (i > 1 && line.charAt(i-1) == '#')) {
                    // Ignore '# description' entries in e.g. route-policy or sets
                    sbin.append(line+"\n");
                } else {
                    // Quote ' description ' strings
                    String desc = stringQuote(line.substring(i+13).trim());
                    sbin.append(line.substring(0,i+13) + desc + "\n");
                }
                continue;
            }

            //
            // alias [exec|config] *
            //
            if (toptag.startsWith("alias ")
                && (match = getMatch(trimmed, "alias (?:exec \\S+|config \\S+|\\S+) (.+)")) != null) {
                input = line.replace(match, stringQuote(match));
            }

            //
            // * route-policy [import|export] <name>
            //
            else if (isDevice
                     && (match = getMatch(line, "route-policy(?: import| export)? (\\S+(?:\\(.+?\\)))")) != null
                     && match.contains(" ")) {
                input = line.replace(match, "\""+match+"\"");
            }

            // Transform lines[n] -> XXX
            if (input != null && !input.equals(lines[n])) {
                if (input.isEmpty()) {
                    traceVerbose(worker, "transformed <= stripped '"+trimmed+"'");
                    continue;
                }
                traceVerbose(worker, "transformed <= '"+trimmed+"' to '"+input.trim()+"'");
                sbin.append(input+"\n");
            } else {
                sbin.append(line+"\n");
            }
        }
        res = sbin.toString();


        //
        // NETSIM - leave early
        //
        if (!isDevice) {
            return res;
        }


        //
        // REAL DEVICES BELOW:
        //

        //
        // LINE-BY-LINE TRANSFORMATIONS
        //
        traceVerbose(worker, "checking input line-by-line");
        lines = res.split("\n");
        final String[] sets = {
            "extcommunity-set rt",
            "extcommunity-set soo",
            "extcommunity-set opaque",
            "rd-set",
            "tag-set",
            "prefix-set",
            "as-path-set",
            "community-set",
            "large-community-set"
        };
        sbin = new StringBuilder();
        int numTrapsDev = 0;
        String trimmed = "";
        String[] group;
        String prevline = "";
        for (n = 0; n < lines.length; prevline = lines[n], n++) {
            String line = lines[n];
            trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // Update toptag
            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = trimmed; // remove \r
            }
            String input = null;
            final String nextline = (n + 1 < lines.length) ? lines[n+1] : "";

            //
            // tailf:cli-range-list-syntax
            //   class-map * / match vlan *
            //
            if (toptag.startsWith("class-map ") && trimmed.startsWith("match vlan ")) {
                input = line.replaceAll("([0-9])( )([0-9])", "$1,$3");
            }

            //
            // tailf:cli-range-list-syntax
            //   class-map * / match dscp *
            //
            else if (toptag.startsWith("class-map ") && trimmed.startsWith("match dscp ")
                     && (match = getMatch(trimmed, "match dscp(?: ipv[46])? (.+)")) != null) {
                input = line.replace(match, match.trim().replace(" ",","));
            }

            //
            // tailf:cli-range-list-syntax
            //   class-map * / match ipv4|6 icmp-code|type
            //
            else if (toptag.startsWith("class-map ") && trimmed.startsWith("match ipv")
                     && (match = getMatch(trimmed, "match ipv(?:4|6) icmp[-](?:code|type) (.+)")) != null) {
                input = line.replace(match, match.trim().replace(" ",","));
            }

            //
            // interface * / encapsulation dot1ad|dot1ad
            //
            else if (toptag.startsWith("interface ")
                     && getMatch(trimmed, "encapsulation(?: ambiguous)? dot1(?:ad|q) (.*)") != null) {
                input = line.replace(" , ", ",");
            }

            //
            // interface * / ipv4 address
            //
            else if (toptag.startsWith("interface ") && trimmed.startsWith("ipv4 address ")) {
                input = line.replaceAll("ipv4 address ([0-9.]+)/(\\d+)", "ipv4 address $1 /$2");
            }

            //
            // snmp-server traps
            //
            else if (apiSnmpServerEnableAllTraps != 0 && line.startsWith("snmp-server traps")) {
                numTrapsDev++;
                continue;
            }

            //
            // snmp-server host *
            // snmp-server vrf * / host *
            //
            else if (toptag.startsWith("snmp-server host ")
                     && (match = getMatch(trimmed,
                                          "snmp-server host \\S+ (?:traps|informs)(?: encrypted| clear)? (\\S+)"))
                     != null) {
                input = line.replace(match, passwordQuote(match));
            } else if (toptag.startsWith("snmp-server vrf ")
                       && (match = getMatch(line, " host \\S+ (?:traps|informs)(?: encrypted| clear)? (\\S+)"))
                       != null) {
                input = line.replace(match, passwordQuote(match));
            }

            //
            // snmp-server community *
            //
            else if (line.startsWith("snmp-server community ")
                     && (match = getMatch(trimmed, "snmp-server community(?: clear| encrypted)? (\\S+)")) != null) {
                input = line.replace(match, passwordQuote(match));
            }

            //
            // snmp-server contact|location
            //
            else if (toptag.startsWith("snmp-server ")
                     && (match = getMatch(trimmed, "snmp-server (?:contact|location) (.+)")) != null) {
                input = line.replace(match, stringQuote(match));
            }

            // Note: pce was changed to pcep in XR 6.5.x
            // segment-routing / traffic-eng / on-demand color * / dynamic / pcep
            // segment-routing / traffic-eng / policy * / candidate-paths / preference * / dynamic / pcep
            else if ("segment-routing".equals(toptag) && "pce".equals(trimmed)) {
                input = line.replace("pce", "pcep");
            }

            //
            // router igmp / interface * / static-group * inc-mask * * inc-mask *
            //
            else if (toptag.startsWith("router igmp") && trimmed.startsWith("static-group ")) {
                input = line.replace(" count ", " source-count ");
                input = input.replace(" inc-mask ", " source-inc-mask ");
                input = input.replaceFirst(" source-inc-mask ", " inc-mask ");
                input = input.replaceFirst(" source-count ", " count ");
            }

            // evpn / evi * / bgp / route-target * stitching-deprecated [deprecated in 6.5.2]
            else if (toptag.startsWith("evpn")) {
                input = line.replace("stitching-deprecated", "stitching");
            }

            // XR bug patch: can't read it's own config in:
            //   lmp / gmpls optical-uni / controller *
            //   mpls ldp / nsr
            //   mpls traffic-eng / attribute-set
            //   router igmp / version
            //
            else if ("lmp".equals(toptag) && "  !\r".equals(line)) {
                input = "  exit\r";
            } else if ("mpls ldp".equals(toptag) && " !\r".equals(line)) {
                input = " exit\r";
            } else if ("mpls traffic-eng".equals(toptag) && " !\r".equals(line)) {
                input = " exit\r";
            } else if ("router igmp".equals(toptag) && "  !\r".equals(line)) {
                input = "  exit\r";
            } else if (toptag.startsWith("router isis") && "  !\r".equals(line)) {
                input = "  exit\r"; // router isis * / interface * / address-family * /
            }

            //
            // !<comment>
            //
            else if (readStripComments
                     && (trimmed.startsWith("!") && trimmed.length() > 1) || line.startsWith("! ")) {
                input = "";
            }

            //
            // route-policy
            //
            else if (toptag.startsWith("route-policy ")) {
                sbin.append(line+"\n");
                // New line list API - prepend line numbers to lines
                if (apiEditRoutePolicy) {
                    String name = toptag.replace("\"", "");
                    ConfPath cp = new ConfPath(operPath+"/edit-list{\""+name+"\"}");
                    String[] lineno = null;
                    if (cdbOper.exists(cp)) {
                        String val = ConfValue.getStringByValue(cp, cdbOper.getElem(cp.append("/lineno")));
                        lineno = val.split(" ");
                    }
                    int indx = 0;
                    int num = 0;
                    for (n = n + 1; n < lines.length; n++) {
                        if (lines[n].trim().equals("end-policy")) {
                            break;
                        }
                        if (lineno != null && indx < lineno.length) {
                            num = Integer.parseInt(lineno[indx++]);
                        } else {
                            num += 10;
                        }
                        sbin.append(Integer.toString(num)+" "+lines[n].trim()+"\n");
                    }
                }
                // Single buffer API - make contents into a single quoted string
                else {
                    StringBuilder policy = new StringBuilder();
                    for (n = n + 1; n < lines.length; n++) {
                        if (lines[n].trim().equals("end-policy")) {
                            break;
                        }
                        policy.append(lines[n] + "\n");
                    }
                    if (policy.length() > 0) {
                        traceVerbose(worker, "transformed <= quoted '"+toptag+"'");
                        sbin.append(stringQuote(policy.toString())+"\n");
                    }
                }
            }

            //
            // group - make contents into a single quoted string
            //
            else if (!apiGroupModeled && toptag.startsWith("group ")) {
                sbin.append(line+"\n");
                StringBuilder content = new StringBuilder();
                for (n = n + 1; n < lines.length; n++) {
                    if (lines[n].trim().equals("end-group")) {
                        break;
                    }
                    content.append(lines[n] + "\n");
                }
                if (content.length() > 0) {
                    traceVerbose(worker, "transformed <= quoted '"+toptag+"'");
                    String quoted = stringQuote(content.toString());
                    quoted = quoted.replace("\\\\", "\\\\\\\\"); // Preserve backslash
                    sbin.append(quoted+"\n");
                }
            }

            //
            // srlg / name *
            //
            else if ("srlg".equals(toptag)
                     && getMatch(trimmed, "^name (\\S+)$") != null
                     && nextline.startsWith("  value ")) {
                input = " "+trimmed+" "+nextline.trim();
                sbin.append(input+"\n");
                n = n + 1;
                traceVerbose(worker, "transformed <= compacted 'srlg / "+input+"'");
                continue;
            }

            //
            // mpls traffic-eng / auto-tunnel backup
            //
            else if ("mpls traffic-eng".equals(toptag)
                     && !"mpls traffic-eng".equals(prevline)
                     && " auto-tunnel backup\r".equals(line)) {
                // XR bugpatch: can't read it's own config
                traceVerbose(worker, "transformed <= injecting 'mpls traffic-eng' for XR bad ordering");
                sbin.append("mpls traffic-eng\n");
            }

            //
            // service-policy input-list *
            // service-policy output-list *
            //
            else if (apiSpList && trimmed.startsWith("service-policy input ")) {
                input = line.replace(" service-policy input ", " service-policy input-list ");
            }
            else if (apiSpList && trimmed.startsWith("service-policy output ")) {
                input = line.replace(" service-policy output ", " service-policy output-list ");
            }

            //
            // policy-map * / class * / random-detect discard-class *
            //
            else if (toptag.startsWith("policy-map ")
                     && trimmed.startsWith("random-detect discard-class ")
                     && trimmed.contains(",")
                     && (group = getMatches(trimmed, "random-detect discard-class (\\S+)( .*)")) != null) {
                // XR concats discard-class entries with a comma between, i.e: x,y,z
                String[] vals = group[1].split(",");
                traceVerbose(worker, "transformed <= splitting '"+trimmed+"' in "+vals.length+" entries");
                for (int v = 0; v < vals.length; v++) {
                    sbin.append("random-detect discard-class "+vals[v]+group[2]+"\n");
                }
                continue;
            }

            //
            // sets - strip commas
            //
            for (int s = 0; s < sets.length; s++) {
                if (line.startsWith(sets[s]+" ") || "policy-global".equals(trimmed)) {
                    traceVerbose(worker, "transformed <= stripped commas from set: "+line);
                    sbin.append(line+"\n");
                    for (n = n + 1; n < lines.length; n++) {
                        if (lines[n].trim().equals("end-set")
                            || lines[n].trim().equals("end-global")) {
                            break;
                        }
                        sbin.append(lines[n].replace(",","")+"\n");
                    }
                    break;
                    // fall through for appending end-set/global line
                }
            }

            //
            // Transform lines[n] -> XXX
            //
            if (n >= lines.length) {
                break;  // Note: 'n' may have been updated above, i.e. 'line' no longer valid
            }
            if (input != null && !input.equals(lines[n])) {
                if (input.isEmpty()) {
                    traceVerbose(worker, "transformed <= stripped '"+line+"'");
                    continue;
                }
                traceVerbose(worker, "transformed <= '"+line+"' to '"+input.trim()+"'");
                sbin.append(input+"\n");
            } else {
                sbin.append(lines[n]+"\n");
            }
        }

        //
        // ned-settings cisco-iosxr api snmp-server-enable-all-traps != 0
        //
        handleAllTrapsInput(worker, numTrapsDev, sbin);

        //
        // group config - do not inject strict|mode fixes
        //
        res = sbin.toString();
        if (!groupName.isEmpty()) {
            return res;
        }

        //
        // main config only:
        //

        //
        // ned-setting cisco-iosxr read show-running-strict-mode
        //
        lines = res.split("\n");
        sbin = new StringBuilder();
        if (showRunningStrictMode) {
            traceInfo(worker, "applying strict mode");
            for (n = 0; n < lines.length; n++) {
                trimmed = lines[n].trim();
                if (lines[n].startsWith("!") && "!".equals(trimmed)) {
                    sbin.append(lines[n].replace("!", "xyzroot 0")+"\n");
                } else if ("!".equals(trimmed)) {
                    sbin.append(lines[n].replace("!", "exit")+"\n");
                } else {
                    sbin.append(lines[n]+"\n");
                }
            }
        }

        // Mode-sensitive fixes:
        else {
            traceVerbose(worker, "in-transforming - injecting 'xyzroot 0' top-root markers");
            for (n = 0; n < lines.length; n++) {
                if (lines[n].startsWith("interface ")) {
                    sbin.append("xyzroot 0\n");
                    for (; n < lines.length; n++) {
                        if (lines[n].equals("!\r")) {
                            sbin.append("exit\n");
                            break;
                        }
                        sbin.append(lines[n]+"\n");
                        if (lines[n].equals("exit\r")) {
                            break;
                        }
                    }
                    continue;
                }
                if (lines[n].startsWith("vrf ")) {
                    sbin.append("xyzroot 0\n");
                }
                sbin.append(lines[n]+"\n");
            }
        }

        // Done
        return sbin.toString();
    }


    /**
     * Inject config in input
     * @param
     * @return modified buffer
     */
    private String injectInput(NedWorker worker, String res) {

        StringBuffer first = new StringBuffer();
        if (apiEditRoutePolicy) {
            traceInfo(worker, "transformed <= inserted 'tailfned api edit-route-policy'");
            first.append("tailfned api edit-route-policy\n");
        }
        if (apiSpList) {
            traceInfo(worker, "transformed <= inserted 'tailfned api service-policy-list'");
            first.append("tailfned api service-policy-list\n");
        }
        if (apiClassMapMatchAGList) {
            traceInfo(worker, "transformed <= inserted 'tailfned api class-map-match-access-group-list");
            first.append("tailfned api class-map-match-access-group-list\n");
        }
        if (apiGroupModeled) {
            traceInfo(worker, "transformed <= inserted 'tailfned api group-modeled");
            first.append("tailfned api group-modeled\n");
        }
        if (apiSnmpServerEnableAllTraps != 0) {
            traceInfo(worker, "transformed <= inserted tailfned api snmp-server-enable-all-traps");
            first.append("tailfned api snmp-server-enable-all-traps\n");
        }

        StringBuffer last = new StringBuffer();
        if (includeCachedShowVersion) {
            // Add cached-show info to config
            last.append("cached-show version version " + iosversion + "\n");
            last.append("cached-show version model " + iosmodel.replace(" ", "-") + "\n");
            last.append("cached-show version serial-number " + iosserial + "\n");
        }

        res = "\n" + first.toString() + res + last.toString();

        //
        // read/replace-config ned-setting - inject/replace in running-config
        //
        if (!replaceConfig.isEmpty()) {
            traceVerbose(worker, "applying read/replace-config ned-setting");
            for (int n = 0; n < replaceConfig.size(); n++) {
                String[] entry = replaceConfig.get(n);
                String regexp = entry[1];
                String replacement = entry[2];
                try {
                    Pattern p = Pattern.compile(regexp+"(?:[\r])?", Pattern.DOTALL);
                    Matcher m = p.matcher(res);
                    StringBuffer sb = new StringBuffer();
                    while (m.find()) {
                        traceInfo(worker, "transformed <= replaced "+stringQuote(m.group(0))
                                  +" with " + matcherToString(m, replacement));
                        m.appendReplacement(sb, replacement);
                    }
                    m.appendTail(sb);
                    res = sb.toString();
                } catch (Exception e) {
                    logError(worker, "ERROR in read/replace-config '"+entry[0]+"' regexp="
                             +stringQuote(regexp)+" replacement="+stringQuote(replacement), e);
                }
            }
        }

        return res;
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
            traceInfo(worker, "BEGIN SHOW-OFFLINE");
            // Append \r if missing to simulate show run on device
            StringBuilder sb = new StringBuilder();
            String[] lines = data.split("\n");
            for (int n = 0; n < lines.length; n++) {
                if (lines[n].endsWith("\r")) {
                    sb.append(lines[n]+"\n");
                } else {
                    sb.append(lines[n]+"\r\n");
                }
            }
            data = sb.toString();
            this.offlineData = data;
            show(worker, toptag);
            traceInfo(worker, "DONE SHOW-OFFLINE");
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
     * Handler called when "commit no-overwrite" is used in NSO.
     * Dumps config from one or many specified locations in the
     * tree instead of dumping everything.
     *
     * @param worker  - The NED worker
     * @param cp      - Paths to dump
     * @throws Exception
     *
     * commit no-overwrite
     * devices partial-sync-from [ <xpath> <xpath>]
     */
    public void showPartial(NedWorker worker, String[] cp) throws Exception {
        final long start = tick(0);
        if (trace) {
            session.setTracer(worker);
        }
        lastGetConfig = null;

        traceInfo(worker, "BEGIN SHOW PARTIAL");
        traceVerbose(worker, Arrays.toString(cp));


        if ("filter-mode".equals(nedSettings.getString("read/partial-show-method"))) {
            showPartialInternal(schema,
                                maapi,
                                turboParserEnable,
                                worker,
                                cp);
            traceInfo(worker, "DONE SHOW PARTIAL (FILTERED) "+tickToString(start));
            return;
        }

        StringBuilder results = new StringBuilder();
        //
        // NETSIM - execute a 'show running' and dump the result
        //
        if (isNetsim()) {
            ArrayList<String> cmdPaths = new ArrayList<>(Arrays.asList(cp));
            for (String cmdPath : cmdPaths) {
                String show = "show running-config " + cmdPath.replace("\\", "");
                String dump = print_line_exec(worker, show);
                if (dump.contains("% No entries found.")) {
                    traceInfo(worker, "showPartial() WARNING: '"+cmdPath+"' not found");
                } else {
                    results.append(dump);
                }
                setReadTimeout(worker);
            }
        }

        //
        // Real XR device
        //
        else {
            ArrayList<String> cmdPaths = new ArrayList<>();
            ArrayList<String> pathsToDump = new ArrayList<>();

            // Scan protectedPaths and trim matches with too deep show
            for (int i = 0; i < cp.length; i++) {
                boolean isProtected = false;
                String path = cp[i];
                // Trim quotes around route-policy name
                String match = getMatch(path, "^route-policy(?: import| export)? \\\"(.*)\\\"$");
                if (match != null && match.contains(" ")) {
                    path = path.replace("\""+match+"\"", match);
                }
                // Scan paths and trim too deep shows
                for (Pattern pattern : protectedPaths) {
                    Matcher matcher = pattern.matcher(path);
                    if (matcher.matches()) {
                        String group = matcher.group(1).trim();
                        String trimmed = path.substring(0, path.indexOf(group));
                        traceVerbose(worker, "partial: trimmed '"+path+"' to '"+trimmed+"'");
                        cmdPaths.add(trimmed);
                        isProtected = true;
                        break;
                    }
                }
                if (!isProtected) {
                    cmdPaths.add(path);
                }
            }

            // Sort the path list such that shortest comes first
            class PathComp implements Comparator<String> {
                public int compare(String o1, String o2) {
                    int x = o1.length();
                    int y = o2.length();
                    if (x < y) {
                        return -1;
                    } else if (x == y) {
                        return 0;
                    } else {
                        return 1;
                    }
                }
            }
            Collections.sort(cmdPaths, new PathComp());

            // Filter out any overlapping paths
            for (String cmdPath : cmdPaths) {
                boolean isUniquePath = true;
                for (String p : pathsToDump) {
                    if (partialPathIn(cmdPath, p)) {
                        isUniquePath = false;
                        break;
                    }
                }
                if (isUniquePath) {
                    pathsToDump.add(cmdPath); // New path to dump
                } else {
                    traceVerbose(worker, "partial: stripped '"+cmdPath+"'");
                }
            }

            // Call all partial show commands
            NedProgress.Progress progress = reportProgressStart(this, NedProgress.READ_PART_CONFIG);
            try {
                for (String cmdPath : pathsToDump) {
                    String dump = doShowPartial(worker, cmdPath);
                    if (dump == null) {
                        throw new NedException("showPartial() :: failed to show '"+cmdPath+"'");
                    }
                    if (!dump.isEmpty()) {
                        // Trim show date stamp
                        Pattern p = Pattern.compile("((?:Tue|Mon|Wed|Thu|Fri|Sat|Sun) .+?(?:MET|UTC))");
                        Matcher m = p.matcher(dump);
                        if (m.find()) {
                            traceVerbose(worker, "transformed <= trimmed "+stringQuote(m.group(1)));
                            dump = dump.replace(m.group(1), "");
                        }
                        // Append output
                        results.append(dump.trim()+"\n");
                        setReadTimeout(worker);
                    }
                }
                reportProgressStop(progress);
            } catch (Exception e) {
                reportProgressStop(progress, NedProgress.ERROR);
                throw e;
            }
        }

        // Transform config
        String config = results.toString();
        if (!config.trim().isEmpty()) {
            config = modifyInput(worker, config);
        }

        traceVerbose(worker, "SHOW-PARTIAL=\n+++ show partial begin\n"+config+"\n+++ show partial end");
        if (devTraceInputBytes) {
            for (int i = 0; i < config.length(); i++) {
                traceVerbose(worker, String.format("[%5d] = %3d = %s", i, (int)config.charAt(i), config.charAt(i)));
            }
        }

        // cisco-iosxr extended-parser
        if (turboParserEnable && parseAndLoadXMLConfigStream(maapi, worker, schema, config)) {
            config = "";
        }

        traceInfo(worker, "DONE SHOW PARTIAL "+tickToString(start));
        worker.showCliResponse(config);
    }

    // @Override
    public void showPartial(NedWorker w, ConfPath[] paths)
        throws Exception {
        showPartialInternal(schema, maapi, turboParserEnable, w, paths);
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
        String config = getConfig(worker, true);
        config = modifyInput(worker, config);
        return config;
    }


    /**
     * Show partial config
     * @param
     * @return
     * @throws Exception
     */
    private String doShowPartial(NedWorker worker, String cmdPath)
        throws NedException, IOException, SSHSessionException {
        final String show = "show running-config " + cmdPath.replace("\\", "");

        String dump;
        if (cmdPath.trim().equals("admin")) {
            dump = getAdminConfig(worker, true);
        } else {
            dump = print_line_exec(worker, show);
        }

        if (dump.contains("% Invalid input detected")) {
            traceInfo(worker, "showPartial() '"+show+"' ERROR: "+dump);
            return "";
        }
        if (dump.contains("% No such configuration item")) {
            return "";
        }

        return dump;
    }


    /**
     * Partial path
     * @param
     * @return
     */
    private boolean partialPathIn(String longp, String shortp) {
        String[] pl = longp.split(" \\\\ ");
        String[] ps = shortp.split(" \\\\ ");
        for (int i = 0; i < ps.length; i++) {
            if (!ps[i].trim().equals(pl[i].trim())) {
                return false;
            }
        }
        return true;
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
        traceInfo(worker, "BEGIN GET-TRANS-ID ("+transIdMethod+")");

        // Reconnect to device if remote end closed connection due to being idle
        if (session.serverSideClosed() || "reconnect".equals(failphase)) {
            failphase = "";
            reconnectDevice(worker);
        }

        // Get transaction id
        String res = this.lastTransactionId;
        if (res != null) {
            traceInfo(worker, "Using cached transaction-id = "+this.lastTransactionId);
        } else {
            res = doGetTransId(worker, null);
        }

        // Clear data cache
        clearDataCache();

        // Done
        traceInfo(worker, "DONE GET-TRANS-ID ("+res+") "+tickToString(start));
        worker.getTransIdResponse(res);
    }


    /**
     * Retrieve and calculate transaction-id
     * @param
     * @throws Exception
     */
    private String doGetTransId(NedWorker worker, String res) throws Exception {

        final long start = tick(0);
        traceInfo(worker, "Calculating transaction id...");

        //
        // cisco-iosxr read transaction-id-method commit-list (default)
        //
        if (!adminLogin && "commit-list".equals(transIdMethod)) {
            NedProgress.Progress progress = reportProgressStart(this, NedProgress.GET_TRANS_ID);
            try {
                // Get commit id
                res = getCommitId(worker);

                // Get admin commit id
                if (isDevice() && readAdminShowRun) {
                    res += getAdminTransId(worker);
                }

                // Done
                reportProgressStop(progress);
                traceInfo(worker, "Calculating transaction id done "+tickToString(start));
                return res;

            } catch (Exception e) {
                traceInfo(worker, "NOTICE: Fallback to config-hash transaction-id: "+e.getMessage());
                reportProgressStop(progress, NedProgress.ERROR);
            }
        }

        //
        // cisco-iosxr read transaction-id-method config-hash
        //

        // Use last cached transformed config from applyConfig() secret code
        if (res == null) {
            if (this.lastGetConfig != null) {
                res = this.lastGetConfig;
                this.lastGetConfig = null;
            } else {
                // Get running-config, including admin config
                res = getConfig(worker, false);
                setReadTimeout(worker);
            }
        }

        // Trim tailfned
        StringBuilder sb = new StringBuilder();
        String[] lines = res.trim().split("\n");
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("tailfned ")) {
                continue;
            }
            sb.append(lines[i]+"\n");
        }
        res = sb.toString();

        // Calculate checksum of running-config
        traceVerbose(worker, "TRANS-ID-BUF=\n+++ begin\n"+res+"\n+++ end");
        res = calculateMd5Sum(res);

        // Done
        traceInfo(worker, "Calculating transaction id done "+tickToString(start));
        return res;
    }


    /**
     * Get admin transaction id
     * @param
     * @throws Exception
     */
    private String getAdminTransId(NedWorker worker) throws Exception {

        // Enter admin mode
        if (!enterAdmin(worker)) {
            return "";
        }

        // Get last commit id and use for transaction id
        try {
            traceInfo(worker, "reading admin commit id");
            return "+" + getCommitId(worker);
        } catch (Exception e) {
            traceInfo(worker, "NOTICE: Fallback to config-hash admin transaction-id: "+e.getMessage());
            final String dump = getAdminConfig(worker, false);
            return "+" + calculateMd5Sum(dump);
        } finally {
            exitAdmin(worker);
        }
    }


    /**
     * Get commit id to use as transaction id
     * @param
     * @throws Exceptions
     */
    private String getCommitId(NedWorker worker) throws NedException, IOException, SSHSessionException {

        // First try showing only 1 commit id
        String res = print_line_exec(worker, "show configuration commit list 10");
        if (res.contains("The commit database is empty")) {
            throw new NedException("empty commit database");
        }

        // RP/0/RSP0/CPU0:asr9k-3#show config commit list 2
        // Thu Dec 10 07:07:13.419 UTC
        // SNo. Label/ID              User      Line                Client      Time Stamp
        // ~~~~ ~~~~~~~~              ~~~~      ~~~~                ~~~~~~      ~~~~~~~~~~
        // 1    1000730892            cisco     vty0:node0_RSP0_CP  CLI         Thu Dec 10 02:00:43 2020
        // 2    1000730891            cisco     vty0:node0_RSP0_CP  CLI         Thu Dec 10 02:00:30 2020
        if (!isExecError(res)) {
            int i = res.indexOf("~~~~");
            if (i > 0) {
                String[] lines = res.substring(i).split("\n");
                for (int n = 1; n < lines.length; n++) {
                    String[] column = lines[n].split(" +");
                    if (apiSnmpServerEnableAllTraps != 0 && "MIBD".equals(column[4])) {
                        traceDev(worker, "transaction-id ignoring: "+lines[n]);
                        continue;
                    }
                    traceVerbose(worker, "transaction-id using: "+lines[n]);
                    return column[1];
                }
                throw new NedException("failed to find commit list id: "+stringQuote(res));
            }
        }

        // Second try showing all commit ids (admin mode on NCS)
        res = print_line_exec(worker, "show configuration commit list");
        if (isExecError(res)) {
            throw new NedException(res);
        }
        if (res.contains("The commit database is empty")) {
            throw new NedException("empty commit database");
        }
        if ((res = getMatch(res, "\n(\\d+) ")) != null) {
            return res;
        }

        // Failure
        throw new NedException("failed to show last commit list id");
    }


    /*
     **************************************************************************
     * prepareDry
     **************************************************************************
     */

    /**
     * Display config for commit dry-run
     * @param
     * @throws Exception
     */
    @Override
    public void prepareDry(NedWorker worker, String data) throws Exception {
        final long start = tick(0);
        if (trace && session != null) {
            session.setTracer(worker);
        }
        final int nsoRunningVersion = getNsoRunningVersion();
        if (nsoRunningVersion < 0x7000000) {
            traceInfo(worker, "\n"+data);
        }

        String log = "BEGIN PREPARE-DRY (model="+iosmodel+" version="+iosversion+")";
        if (session == null) {
            log += " [offline]";
        }

        // ShowRaw used in debugging, to see cli commands before modification
        if (showRaw || data.contains("tailfned raw-run\n")) {
            traceInfo(worker, log + " (raw)");
            showRaw = false;
            traceInfo(worker, "DONE PREPARE-DRY (raw)"+tickToString(start));
            worker.prepareDryResponse(data);
            return;
        }

        traceInfo(worker, log);

        // Clear cached data
        clearDataCache();
        this.isDry = true;

        // Modify data buffer
        final int fromTh = worker.getFromTransactionId();
        final int toTh = worker.getToTransactionId();
        try {
            StringBuilder sb = new StringBuilder();
            if (session == null && logVerbose) {
                sb.append("! Generated offline\n");
            }

            // Trigger custom extensions
            data = modifyOutputTurbo(worker, data);
            maapiAttach(worker, fromTh, toTh);

            // Modify output and add to StringBuilder
            String[] lines = modifyOutput(worker, data, fromTh, toTh, "PREPARE-DRY");
            for (int n = 0; n < lines.length; n++) {
                sb.append(lines[n]+"\n");
            }

            // Show and trigger custom extensions on delayedCommit
            if (delayedCommit.length() > 0) {
                traceInfo(worker, "Handling delayedCommit cli:secret extensions");
                parseCLIDiffWithExtensions(worker, delayedCommit.toString(), true,
                                           Arrays.asList(new String[]{"cli:secret"}));
                sb.append("commit\n");
                sb.append(delayedCommit);
            }

            traceInfo(worker, "DONE PREPARE-DRY"+tickToString(start));
            worker.prepareDryResponse(sb.toString());

        } finally {
            maapiDetach(worker, fromTh, toTh);
        }
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
     * Apply config
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
        traceInfo(worker, "BEGIN APPLY-CONFIG");

        // Apply the config
        doApplyConfig(worker, data);

        traceInfo(worker, "DONE APPLY-CONFIG "+tickToString(start));
    }


    /**
     * Apply config on device
     * @param
     * @throws Exceptions
     */
    private void doApplyConfig(NedWorker worker, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {

        if (applyDelay > 0) {
            sleep(worker, (long)applyDelay * 1000, true);
        }

        // commit show-error
        if (iosversion.startsWith("4") || isNetsim() || adminLogin) {
            supportCommitShowError = false;
            commitCommand = commitCommand.replace(" show-error", "");
        }
        traceInfo(worker, "commit show-error = " + supportCommitShowError);

        // Clear global config data
        clearDataCache();
        this.isDry = false;

        try {
            // Trigger custom extensions (must be done on absolute data or bad CDB paths)
            data = modifyOutputTurbo(worker, data);

            // Send and strip admin config
            data = commitAdminConfig(worker, data);

            // Send standard config
            if (!data.trim().isEmpty()) {
                if (adminLogin) {
                    throw new NedException("admin mode login, can't commit non-admin config");
                }
                sendConfig(worker, data, true);
                // In config mode
            }

            // Trigger customer extensions on delayedCommit config
            if (delayedCommit.length() > 0) {
                traceInfo(worker, "Handling delayedCommit cli:secret extensions");
                parseCLIDiffWithExtensions(worker, delayedCommit.toString(), true,
                                           Arrays.asList(new String[]{"cli:secret"}));
            }

            // ned-settings cisco-iosxr api snmp-server-enable-all-traps != 0
            if (this.snmpAllTraps == 1) {
                // Add a 2nd snmp-server traps + commit to properly populate asr9k-2 traps (yes weird!)
                traceInfo(worker, "Adding a second 'snmp-server traps' + commit to restore all traps");
                delayedCommit.append("snmp-server traps\n");
            }

        } catch (NedException|IOException|SSHSessionException|ApplyException e) {
            String reply = e.getMessage();
            if (!session.serverSideClosed()) {
                if (reply.contains("Failed to commit") && reply.contains("show configuration failed")) {
                    reply = print_line_exec(worker, "show configuration failed");
                }
                session.print("abort\n");
            }
            inConfig = false;
            throw new ApplyException(reply, false, false);
        }
    }


    /**
     * Clear cached data
     * @param
     */
    private void clearDataCache() {
        this.lastGetConfig = null;
        this.lastTransactionId = null;
        this.numCommit = 0;
        this.numAdminCommit = 0;
        this.snmpAllTraps = 0;
    }


    /**
     * Modify output using turbo
     * @param
     * @return
     * @throws NedException
     */
    private String modifyOutputTurbo(NedWorker worker, String data) throws NedException {
        setupNedDiff(worker);
        traceInfo(worker, "turbo-mode parsing...");
        final long startTurbo = tick(0);
        this.extInjectFirst = new StringBuilder();
        this.delayedCommit = new StringBuilder();
        data = parseCLIDiff(worker, data);
        traceInfo(worker, "turbo-mode parsing done "+tickToString(startTurbo));
        return this.extInjectFirst.toString() + data;
    }


    /**
     * Commit admin config
     * @param
     * @return
     * @throws Exception
     */
    private String commitAdminConfig(NedWorker worker, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {
        if (isNetsim()) {
            return data;
        }

        // Extract admin config and update data
        data = "\n" + data;
        int start = data.indexOf("\nadmin\n");
        if (start < 0) {
            return data;
        }
        int end = data.indexOf("\n exit-admin-config\n!");
        if (end <= start) {
            return data;
        }
        String adminData = data.substring(start + 7, end + 1);
        data = data.substring(end + 21);

        if (!readAdminShowRun) {
            throw new NedException("commiting admin config not supported with ned-setting "
                                   +"'read admin-show-running-config = false'");
        }

        traceInfo(worker, "Applying "+adminData.length()+" bytes of admin config");

        // Enter exec admin mode
        if (!enterAdmin(worker)) {
            throw new NedException("Admin mode has been deprecated, disable it with ned-settings");
        }

        // Enter config mode and send admin config
        sendConfig(worker, adminData, false);

        // Commit admin config
        print_line_wait_oper(worker, "commit", writeTimeout);
        numAdminCommit++;

        // Exit admin config mode
        exitConfig(worker, "admin");

        // Exit admin mode
        exitAdmin(worker);

        // Return remaining config to apply
        return data;
    }


    /**
     * Modify and send config to device
     * @param
     * @throws Exception
     */
    private void sendConfig(NedWorker worker, String data, boolean useSftp)
        throws NedException, IOException, SSHSessionException, ApplyException {

        //
        // Modify data
        //
        int fromTh = worker.getFromTransactionId();
        int toTh = worker.getToTransactionId();
        String[] lines = null;
        try {
            maapiAttach(worker, fromTh, toTh);
            lines = modifyOutput(worker, data, fromTh, toTh, "APPLY-CONFIG");
        } catch (Exception e) {
            maapiDetach(worker, fromTh, toTh);
            throw e;
        }

        //
        // Send data
        //
        NedProgress.Progress progress = reportProgressStart(this, NedProgress.SEND_CONFIG);
        try {
            // Refresh read timeout
            lastTimeout = setReadTimeout(worker);

            // Enter config mode
            enterConfig(worker);

            // Check if should disable SFTP
            if (useSftp) {
                // Internal commit (injected by customer?), can't use SFTP to apply config
                if (data.contains("\ncommit")) {
                    traceInfo(worker, "Disabling SFTP transfer due to embedded commit command");
                    useSftp = false;
                }
                // XR bug
                if (data.contains("no tacacs-server host ")) {
                    traceInfo(worker, "Disabling SFTP transfer due to XR bug with 'tacacs-server host' config");
                    useSftp = false;
                }
            }

            // Send config
            if (useSftp && lines.length >= writeSftpThreshold) {
                // Use SFTP to upload file and then load to candidate
                sftpPutConfig(worker, linesToString(lines));
            } else {
                // Send config using CLI
                doSendConfig(worker, lines);
            }

            // Done
            reportProgressStop(progress);

        } catch (Exception e) {
            reportProgressStop(progress, NedProgress.ERROR);
            throw e;

        } finally {
            maapiDetach(worker, fromTh, toTh);
        }
    }


    /**
     * Send config lines to device using CLI
     * @param
     * @throws Exception
     */
    private void doSendConfig(NedWorker worker, String[] lines)
        throws NedException, IOException, SSHSessionException, ApplyException {
        final long startSending = tick(0);

        traceInfo(worker, "BEGIN sending "+lines.length+" line(s)");
        String line = "";
        ModeStack modeStack = new ModeStack();
        boolean traceDisabled = false;
        try {
            // Send chunk of chunkSize
            int length = lines.length;
            for (int n = 0; n < lines.length;) {

                // Update timeout each TIMEOUT_MOD
                if ((n % TIMEOUT_MOD) == 0) {
                    lastTimeout = resetReadTimeout(worker, lastTimeout);
                }

                // Copy in up to chunkSize config commands in chunk
                StringBuilder sb = new StringBuilder();
                StringBuilder tracesb = new StringBuilder();
                boolean haveSecrets = false;
                int num = 0;
                final int start = n;
                for (; n < lines.length; n++) {
                    if (num == chunkSize) {
                        break;
                    }

                    line = lines[n];
                    if (line == null || line.isEmpty()) {
                        length--;
                        continue;
                    }

                    // Ignore '!'
                    String trimmed = line.trim();
                    if ("!".equals(trimmed)) {
                        length--;
                        continue;
                    }

                    // XR aa ae oe character twerk [RT30152]
                    if (trimmed.startsWith("description ")) {
                        line = trimmed;
                    }

                    // line contains a Maapi encrypted string then decrypt (disable tracing?)
                    tracesb.append(line + "\n");
                    if (line.contains(" $4$") || line.contains(" $7$")
                        || line.contains(" $8$") || line.contains(" $9$")) {
                        line = maapiDecryptLine(worker, line);
                        if (!line.equals(lines[n])) {
                            lines[n] = line; // update to match expect echo
                            if (!devTraceEnable) {
                                haveSecrets = true;
                            }
                        }
                    }

                    // Append line
                    sb.append(line + "\n");
                    num++;
                }
                if (num == 0) {
                    break;
                }

                traceInfo(worker, "Sending line(s) "+(start+1)+"-"+(start+num)+"/"+length);

                if (haveSecrets && trace) {
                    traceInfo(worker, "\n"+tracesb.toString());
                    traceInfo(worker, "Disabling tracing due to Maapi-decrypted secret(s)");
                    traceDisabled = true;
                    session.setTracer(null);
                }

                // Send chunk of 'num' line(s) to the device
                String chunk = sb.toString();
                session.print(chunk);

                // Check device reply, one line at the time
                String[] echos = tracesb.toString().split("\n");
                for (int i = start; i < n; i++) {
                    line = lines[i];
                    if (line == null || line.isEmpty()) {
                        continue;
                    }

                    // Update modeStack
                    modeStack.update(line);

                    // Ignore !
                    if ("!".equals(line.trim())) {
                        continue;
                    }

                    // Check device echo and possible input error
                    noprint_line_wait(worker, line);
                }

                // Sent chunk, enable trace again
                if (traceDisabled) {
                    traceDisabled = false;
                    session.setTracer(worker);
                    traceInfo(worker, "Received echo of "+num+" sent line(s)\n");
                }
            }

            // Done, make sure we have exited from all sub-modes
            if (traceDisabled) {
                session.setTracer(worker);
            }
            moveToTopConfig(worker);

            // Debug code:
            if ("prepare".equals(failphase)) {
                failphase = "";
                throw new NedException("PREPARE :: debug exception in prepare");
            }

            traceInfo(worker, "DONE sending "+lines.length+" line(s) "+tickToString(startSending));

        } catch (Exception e) {
            if (traceDisabled) {
                session.setTracer(worker);
            }
            throw new NedException(e.getMessage()+modeStack.toString(), e);
        }
    }


    /**
     * Maapi decrypt a password
     * @param
     * @return
     */
    private String maapiDecrypt(String secret) {
        try {
            if (this.mCrypto == null) {
                this.mCrypto = new MaapiCrypto(maapi);
            }
            return mCrypto.decrypt(secret);
        } catch (MaapiException e) {
            // Ignore Exception
        }
        return secret;
    }


    /**
     * Maapi.decrypt secret(s) in a config line
     * @param
     * @return
     */
    private String maapiDecryptLine(NedWorker worker, String line) {
        if (isNetsim()) {
            return line;
        }
        Pattern p = Pattern.compile("( \\$[4789]\\$[^\\s]*)");
        Matcher m = p.matcher(line);
        while (m.find()) {
            String password = line.substring(m.start() + 1, m.end());
            try {
                password = password.replace("\\n", ""); // NSO 5.3.2.2 bug patch
                String decrypted = mCrypto.decrypt(password);
                traceVerbose(worker, "transformed => Maapi.decrypted '"+password+"'");
                line = line.substring(0, m.start()+1) + decrypted + line.substring(m.end(), line.length());
            } catch (Exception e) {
                // Ignore exceptions, since can't tell if password is NSO or device encrypted
                traceInfo(worker, "Failed to Maapi.decrypt '"+password+"' : "+e.getMessage());
                return line;
            }
            m = p.matcher(line);
        }
        return line;
    }


    /**
     * Modify output data
     * @param
     * @return
     * @throws NedException
     */
    private String[] modifyOutput(NedWorker worker, String data, int fromTh, int toTh, String function)
        throws NedException {
        this.confPath = this.confRoot;
        NedProgress.Progress progress = reportProgressStart(this, NedProgress.TRANSFORM_OUT);
        try {
            String[] lines;

            // Single string group:
            if (!apiGroupModeled) {
                lines = modifyOutputGroup(worker, data, fromTh, toTh);
            }

            // Detailed groups:
            else {
                // Split config in main + optional groups
                List<String> parts = splitConfigInGroups(data, false);
                ArrayList<String> linesList = new ArrayList<>();
                for (String dump : parts) {

                    // Modify main output config
                    if (!dump.startsWith("group ")) {
                        lines = modifyOutputGroup(worker, dump, fromTh, toTh);
                        for (String line : lines) {
                            linesList.add(line);
                        }
                        continue;
                    }

                    // Group config
                    lines = dump.split("\n");
                    final String group = lines[0].substring(6).trim();
                    this.confPath = this.confRoot + "group{"+group+"}";

                    // Add group header
                    linesList.add(lines[0]);

                    // Shift left 1 to look like top
                    StringBuilder sbc = new StringBuilder();
                    for (int n = 1; n < lines.length; n++) {
                        if (" end-group".equals(lines[n])) {
                            break;
                        }
                        sbc.append(lines[n].substring(1)+"\n");
                    }

                    // Modify group output config
                    lines = modifyOutputGroup(worker, sbc.toString(), fromTh, toTh);

                    // Shift right 1 and add group footer
                    for (String line : lines) {
                        linesList.add(" "+line);
                    }
                    linesList.add(" end-group");
                    linesList.add("!");
                }
                lines = linesList.toArray(new String[linesList.size()]);
            }

            // Done
            reportProgressStop(progress);
            //traceDev(worker, function+"_AFTER:\n"+linesToString(lines));
            if (delayedCommit.length() > 0) {
                traceInfo(worker, function+"_DELAYED_COMMIT:\n"+delayedCommit.toString());
            }
            return lines;

        } catch (Exception e) {
            reportProgressStop(progress, NedProgress.ERROR);
            throw e;

        } finally {
            this.confPath = this.confRoot;
        }
    }


    /**
     * Split running-config in main + optional group config
     * @param
     * @return
     */
    private List<String> splitConfigInGroups(String data, boolean input) {
        StringBuilder main = new StringBuilder();
        List<String> parts = new ArrayList<>();
        String[] lines = data.split("\n");
        for (int n = 0; n < lines.length; n++) {
            // main config
            if (!lines[n].startsWith("group ")) {
                main.append(lines[n]+"\n");
                continue;
            }
            // group config
            StringBuilder sb = new StringBuilder();
            for (; n < lines.length; n++) {
                sb.append(lines[n]+"\n");
                if (isTopExit(lines[n])
                    || (input && "end-group".equals(lines[n].trim()))) {
                    parts.add(sb.toString());
                    break;
                }
            }
        }
        // append main last
        parts.add(main.toString());
        return parts;
    }


    /**
     * Modify output group
     * @param
     * @return
     * @throws NedException
     */
    private String[] modifyOutputGroup(NedWorker worker, String data, int fromTh, int toTh)
        throws NedException {

        //
        // Edit data
        //
        if (apiEditRoutePolicy) {
            if (isDevice()) {
                data = editOutputData(worker, data, toTh);
            } else if (!this.isDry) {
                if (getMatch(data, "(route-policy \\S+\n \\d+ )") != null) {
                    traceVerbose(worker, "injecting 'tailfned api edit-route-policy' for NETSIM support");
                    data = "tailfned api edit-route-policy\n" + data;
                }
            }
        }

        //
        // Modify sets
        //
        if (isDevice()) {
            traceVerbose(worker, "preparing sets");
            data = modifyOutputSets(worker, data, toTh);
        }

        //
        // Reorder output config
        //
        traceVerbose(worker, nedDiff.toString());
        data = "\n" + nedDiff.reorder(worker, data);

        //
        // ned-settings cisco-iosxr auto CSCtk60033-patch - delete class-maps used in policy-maps last
        //
        if (autoCSCtk60033Patch && data.contains("\npolicy-map ")) {
            traceVerbose(worker, "applying CSCtk60033 patch");
            data = outputPatchCSCtk60033(worker, data, fromTh);
        }

        //
        // ned-settings cisco-iosxr auto aaa-tacacs-patch - delete aaa group server tacacs last
        //
        if (autoAaaTacacsPatch && data.contains("aaa authentication login default")
            && data.contains("\nno aaa group server tacacs")) {
            traceVerbose(worker, "applying aaa-tacacs patch");
            data = outputPatchAAATacacs(worker, data);
        }

        //
        // LINE-BY-LINE - applyConfig
        //
        traceVerbose(worker, "checking output line-by-line");
        String toptag = "";
        String[] group;
        String[] lines = data.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            String output = null;
            boolean traceChange = true;
            final String cmdtrim = trimmed.startsWith("no ") ? trimmed.substring(3) : trimmed;

            // Update toptag
            if (Character.isLetter(line.charAt(0))) {
                toptag = trimmed;
            }

            //
            // NETSIM and DEVICE
            //

            //
            // policy-map * / class * / police rate
            // NSO bug, track #15958
            //
            if (toptag.startsWith("policy-map ") && "police rate".equals(trimmed)) {
                output = "  no police";
            }

            //
            // cached-show
            //
            else if (cmdtrim.startsWith("cached-show ")) {
                output = "!" + line;
            }

            //
            // service-policy input-list *
            // service-policy output-list *
            //
            else if (apiSpList && cmdtrim.startsWith("service-policy input-list ")) {
                output = line.replace(" service-policy input-list ", " service-policy input ");
            } else if (apiSpList && cmdtrim.startsWith("service-policy output-list ")) {
                output = line.replace(" service-policy output-list ", " service-policy output ");
            }

            // xyzexit [debug trick]
            if (line.startsWith("xyzexit ")) {
                for (int e = 0; e < Integer.parseInt(line.substring(8)); e++) {
                    sb.append("exit\n");
                }
                continue;
            }

            //
            // NETSIM
            //
            if (isNetsim()) {
                int i;

                // description patch for netsim, quote text and escape "
                if (cmdtrim.startsWith("description ") && (i = line.indexOf("description ")) >= 0) {
                    String desc = line.substring(i+12).trim(); // Strip initial white spaces, added by NCS
                    if (desc.charAt(0) != '"') {
                        desc = desc.replaceAll("\\\"", "\\\\\\\""); // Convert " to \"
                        output = line.substring(0,i+12) + "\"" + desc + "\""; // Quote string, add ""
                    }
                }

                // no alias Java 'tailf:cli-no-value-on-delete' due to NCS bug
                else if (line.startsWith("no alias ") &&
                         (group = getMatches(line, "(no alias(?: exec| config)? \\S+ ).*")) != null) {
                    output = group[1];
                }

                // alias patch
                else if (line.startsWith("alias ") &&
                         (group = getMatches(line, "(alias(?: exec| config)? \\S+ )(.*)")) != null) {
                    String alias = group[2].replaceAll("\\\"", "\\\\\\\""); // Convert " to \"
                    output = group[1] + "\"" + alias + "\""; // Quote string, add ""
                }

                // Fall through for adding line (transformed or not)
            }


            //
            // DEVICE
            //
            else {
                String match;

                //
                // route-policy
                //
                if (trimmed.contains("route-policy ") && !cmdtrim.startsWith("description ")) {
                    lines[n] = line.replace("\"", ""); // Strip quotes around name
                    // Note: Fall through without 'else if' to check additional transforms
                }

                //
                // tailfned api
                //
                if (cmdtrim.startsWith("tailfned api")) {
                    output = "!" + line;
                }


                //
                // cisco-iosxr auto acl-delete-patch true with:
                //  no ipv4 access-list *
                //  no ipv6 access-list *
                //
                else if (autoAclDeletePatch
                         && (line.startsWith("no ipv4 access-list ") || line.startsWith("no ipv6 access-list "))
                         && (match = getMatch(line, " access-list (\\S+)")) != null
                         && getMatch(data, "\n no match access-group (ipv4|ipv6) "+match+"\n") != null) {
                    traceInfo(worker, "transformed => applied acl-delete-patch, applying '"
                              +trimmed+"' in separate last commit");
                    delayedCommit.append(line+"\n");
                    continue;
                }

                //
                // interface * / ipv4 address x.y.z.w /prefix
                //
                else if (line.matches("^\\s*(no )?ipv4 address \\S+ /(\\d+).*$")) {
                    output = line.replaceFirst(" (\\S+) /(\\d+)", " $1/$2");
                }

                //
                // class-map * / match vlan [inner] *
                // class-map * / match dscp *
                // class-map * / match ipv4|ipv6 icmp-*
                //
                else if (toptag.startsWith("class-map ")
                         && (cmdtrim.startsWith("match vlan ")
                             || cmdtrim.startsWith("match ipv4 icmp-")
                             || cmdtrim.startsWith("match ipv6 icmp-")
                             || cmdtrim.startsWith("match dscp "))) {
                    output = line.replace(",", " ");
                }

                //
                // no group *
                //
                else if (line.startsWith("no group ")) {
                    // Note: Needed twice on real device for some obscure reason
                    sb.append(line+"\n");
                }

                //
                // snmp-server host *
                // snmp-server vrf * / host *
                //
                else if (cmdtrim.startsWith("snmp-server host ")
                         && (match = getMatch(trimmed,
                                              "snmp-server host \\S+ (?:traps|informs)(?: encrypted| clear)? (\\S+)"))
                         != null) {
                    output = line.replace(match, textDequote(match));
                } else if (toptag.startsWith("snmp-server vrf ")
                           && (match = getMatch(line, " host \\S+ (?:traps|informs)(?: encrypted| clear)? (\\S+)"))
                           != null) {
                    output = line.replace(match, textDequote(match));
                }

                //
                // snmp-server location|contact *
                //
                else if (cmdtrim.startsWith("snmp-server ")
                         && (group = getMatches(line, "(snmp-server (?:location|contact) )[ ]*(.+)")) != null) {
                    output = group[1] + textDequote(group[2]);
                }

                //
                // interface tunnel-te* / path-selection
                //
                else if (iosversion.startsWith("5")
                         && toptag.startsWith("interface tunnel-te")
                         && " path-selection".equals(line)) {
                    for (n = n + 1; n < lines.length; n++) {
                        if (lines[n].startsWith("  no ")) {
                            sb.append(lines[n].replace("  no ", " no path-selection ")+"\n");
                        } else if (lines[n].startsWith("  ")) {
                            sb.append(" path-selection "+lines[n].trim()+"\n");
                        } else {
                            break;
                        }
                    }
                    continue; // flush ' exit'
                }

                //
                // router igmp / interface * / static-group * inc-mask * * inc-mask *
                //
                else if (toptag.startsWith("router igmp") && (trimmed.startsWith("static-group ") || trimmed.startsWith("no static-group ") )) {
                    output = line.replace(" source-", " ");
                }

                //
                // mpls ldp / label / accept / from * for
                //
                else if ("mpls ldp".equals(toptag)
                         && line.startsWith("   from ") && line.contains(" for ")) {
                    output = line.replaceFirst("from (\\S+) for (\\S+)", "for $2 from $1");
                }

                //
                // aaa attribute format * / format-string
                // dhcp ipv4 / interface * information option format-type ? format-string
                //
                else if (((toptag.startsWith("aaa attribute format ") && cmdtrim.startsWith("format-string "))
                          || (toptag.startsWith("dhcp ipv") && line.contains(" format-string ")))
                         && (match = getMatch(trimmed, "format-string(?: length \\d+)? (\\S+)")) != null
                         && !match.startsWith("\"")) {
                    output = line.replace(match, "\""+match+"\"");
                }

                // Note: pce was changed to pcep in XR 6.5.x
                // segment-routing / traffic-eng / on-demand color * / dynamic / pcep
                // segment-routing / traffic-eng / policy * / candidate-paths / preference * / dynamic / pcep
                //
                else if ("segment-routing".equals(toptag) && "pcep".equals(trimmed)) {
                    output = line.replace("pcep", "pce");
                }

                //
                // route-policy * / xxx
                // group * / xxx
                //
                else if ((line.startsWith("route-policy ") && !apiEditRoutePolicy)
                         || (line.startsWith("group ") && !apiGroupModeled)) {
                    // Dequote and split single quoted string, example:
                    // route-policy <NAME>
                    //   "if (xxx) then \r\n statement(s) \r\n endif\r\n"
                    //  end-policy
                    traceChange = false;
                    if (line.startsWith("group ")
                        && maapiExists(worker,fromTh,confRoot+"group{"+line.replace("group ","").trim()+"}/line")) {
                        sb.append("no "+line+"\n"); // group does not reset old contents
                    }
                    sb.append(lines[n++]+"\n");
                    if (lines[n].trim().startsWith("no ")) {
                        continue; // Ignore delete, entries are all reset
                    } else if (lines[n].trim().startsWith("\"")) {
                        traceVerbose(worker, "transformed => dequoted "+trimmed);
                        String value = stringDequote(lines[n++].trim());
                        String[] values = value.split("\n");
                        for (int v = 0; v < values.length; v++) {
                            sb.append(values[v].replace("\r", "")+"\n");
                        }
                        // note: end-policy will be added in next loop
                    }
                }

                // Fall through for adding line (transformed or not)
            }

            // Need terminal reset
            if (toptag.startsWith("line ") && cmdtrim.startsWith("length")) {
                resetTerminal = true;
            }

            //
            // Transform lines[n] -> XXX
            //
            if (output != null && !output.equals(lines[n])) {
                if (output.isEmpty()) {
                    if (traceChange) {
                        traceVerbose(worker, "transformed => stripped '"+trimmed+"'");
                    }
                } else {
                    if (traceChange) {
                        traceVerbose(worker, "transformed => '"+trimmed+"' to '"+output.trim()+"'");
                    }
                    sb.append(output+"\n");
                }
            }

            // Append to sb
            else if (lines[n] != null && !lines[n].isEmpty()) {
                sb.append(lines[n]+"\n");
            }
        }
        data = "\n" + sb.toString();

        //
        // Inject command(s)
        //
        if (!injectCommand.isEmpty()) {
            traceVerbose(worker, "applying write/inject-command ned-setting");
            for (int n = 0; n < injectCommand.size(); n++) {
                String[] entry = injectCommand.get(n);
                data = injectOutputData(worker, data, entry, "=>");
            }
        }

        //
        // NETSIM END
        //
        lines = data.trim().split("\n");
        if (isNetsim()) {
            return lines;
        }

        //
        // DEVICE ONLY:
        //
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            //
            // banner <type> <marker> "<message>" <marker>
            //
            if (trimmed.startsWith("banner ")) {
                Pattern p = Pattern.compile("banner (\\S+) (\\S+) (.*) (\\S+)");
                Matcher m = p.matcher(line);
                if (m.find()) {
                    String marker = m.group(2);
                    if (marker.charAt(0) == '"') {
                        marker = passwordDequote(marker);
                    }
                    String message = textDequote(m.group(3));
                    message = message.replace("\r", "");  // device adds \r itself
                    traceVerbose(worker, "transformed => dequoted banner "+m.group(1));
                    lines[n] = "banner "+m.group(1)+" "+marker+message+marker;
                }
            }

        }

        return lines;  // Note: Must return lines since banner contains multiple \n
    }


    /**
     * Edit output data
     * @param
     * @return
     * @throws NedException
     */
    private String editOutputData(NedWorker worker, String data, int toTh) throws NedException {

        String[] lines = data.split("\n");
        int n = 0;
        try {
            StringBuilder sb = new StringBuilder();
            for (; n < lines.length; n++) {
                String line = lines[n];
                String trimmed = line.trim();
                String cmd = trimmed.startsWith("no ") ? trimmed.substring(3) : trimmed;

                // route-policy *
                if (line.startsWith("route-policy ")) {
                    sb.append(line+"\n");
                    // Read to-transaction and create line number oper cache
                    String name = getMatch(cmd, "route-policy\\s+(.+)");
                    String path = confPath+"route-policy-edit/route-policy{"+name+"}";
                    String lineno = editListToStringBuilder(worker, path, toTh, sb);
                    if (!lineno.isEmpty()) {
                        name = line.replace("\"", "");
                        ConfPath cp = new ConfPath(operPath+"/edit-list{\""+name+"\"}");
                        if (!cdbOper.exists(cp)) {
                            cdbOper.create(cp);
                        }
                        cdbOper.setElem(new ConfBuf(lineno.trim()), cp.append("/lineno"));
                    }
                    // Trim line changes from NSO
                    for (; n < lines.length; n++) {
                        if (lines[n].trim().equals("end-policy")) {
                            sb.append(lines[n].trim()+"\n");
                            break;
                        }
                    }
                    continue;
                }

                // no route-policy *
                else if (line.startsWith("no route-policy ")) {
                    // Delete line number oper cache
                    String name = cmd.replace("\"", "");
                    ConfPath cp = new ConfPath(operPath+"/edit-list{\""+name+"\"}");
                    if (cdbOper.exists(cp)) {
                        cdbOper.delete(cp);
                    }
                }

                // Append line
                sb.append(line+"\n");
            }
            data = "\n" + sb.toString();
        } catch (Exception e) {
            throw new NedException("editOutputData '"+lines[n]+"' ERROR: ", e);
        }
        return data;
    }


    /**
     * Modify output sets
     * @param
     * @return
     * @throws NedException
     */
    private String modifyOutputSets(NedWorker worker, String data, int toTh) throws NedException {

        int n = 0;
        String[] lines = data.split("\n");
        try {
            StringBuilder sb = new StringBuilder();
            for (; n < lines.length; n++) {
                String line = lines[n];
                sb.append(line+"\n");

                // Get set config path and list name
                String cpath = setGetPath(line);
                if (cpath == null) {
                    continue;
                }
                String path;
                if ("policy-global".equals(cpath)) {
                    path = confPath + cpath;
                } else {
                    String name = getMatch(line, ".* (\\S+)");
                    path = confPath+cpath.replace(" ", "/")+"{"+name+"}";
                }

                // Read to-transaction to (re-)send all entries, add comma and trim ios-regex
                int num = setListToStringBuilder(worker, path, toTh, sb);
                if (num > 0) {
                    traceInfo(worker, "transformed => restored "+num+" line(s) in "+line.trim());
                }

                // Trim line changes from NSO
                for (; n < lines.length; n++) {
                    if (lines[n].trim().equals("end-set") || lines[n].trim().equals("end-global")) {
                        sb.append(lines[n].trim()+"\n");
                        break;
                    }
                }
            }
            data = "\n" + sb.toString();
        } catch (Exception e) {
            throw new NedException("modifyOutputSets '"+lines[n]+"' ERROR: ", e);
        }
        return data;
    }


    /**
     *
     * @param
     * @return
     */
    private String setGetPath(String line) {
        String [] sets = {
            "rd-set ",
            "tag-set ",
            "prefix-set ",
            "as-path-set ",
            "community-set ",
            "large-community-set ",
            "extcommunity-set rt ",
            "extcommunity-set soo ",
            "extcommunity-set opaque ",
            "policy-global"
        };
        for (int i = 0; i < sets.length; i++) {
            if (line.startsWith(sets[i])) {
                return sets[i].trim();
            }
        }
        return null;
    }


    /**
     * Wait for echo and verify response of previously sent line(s)
     * @param
     * @return
     * @throws Exceptions
     */
    private void noprint_line_wait(NedWorker worker, String line)
        throws NedException, IOException, SSHSessionException {

        // (1) - Expect the echo of the line(s) we sent
        for (String wait: line.trim().split("\n")) {
            //traceDev(worker, "noprint_line_wait() - waiting for echo: "+stringQuote(wait));
            session.expect(new String[] { Pattern.quote(wait) }, worker);
            //traceDev(worker, "noprint_line_wait() - got echo: "+stringQuote(wait));
        }

        // (2) - Wait for the prompt
        String match = "#";
        try {
            //traceDev(worker, "noprint_line_wait() - waiting for prompt");
            NedExpectResult res;
            try {
                res = session.expect(NOPRINT_LINE_WAIT_PATTERN, worker);
            } catch (Exception e) {
                if (session.serverSideClosed()) {
                    throw new NedException("\n% Server side closed");
                }
                throw e;
            }
            match = expectGetMatch(res);
            //traceDev(worker, "noprint_line_wait() - prompt matched("+res.getHit()+"): "+stringQuote(match));
            switch (res.getHit()) {
            case 0:  // "Uncommitted changes found, commit them"
                session.print("no\n"); // Send a 'no'
                throw new NedException("\n% Exited from config mode");
            case 1:  // config prompt
                break;
            default: // exec prompt
                throw new NedException("\n% Exited from config mode");
            }

            // (3) - Look for errors shown on screen after command was sent
            String reply = res.getText();
            if (isCliError(worker, reply)) {
                throw new NedException(reply);
            }

        } catch (Exception e) {
            throw new NedException("command: \n"+match+" "+line+" "+e.getMessage());
        }
    }


    /**
     * Check if device reply is an error
     * @param
     * @return
     */
    private boolean isCliError(NedWorker worker, String reply) {

        reply = reply.trim();
        if (reply.isEmpty()) {
            return false;
        }
        traceDev(worker, "Checking device reply="+stringQuote(reply));

        // Ignore dynamic warnings [case sensitive]
        for (int n = 0; n < dynamicWarning.size(); n++) {
            if (findString(dynamicWarning.get(n), reply) >= 0) {
                traceInfo(worker, "ignoring dynamic warning: '"+reply+"'");
                return false;
            }
        }

        // Check device error keywords:
        reply = reply.toLowerCase();
        for (int n = 0; n < staticErrors.length; n++) {
            if (reply.contains(staticErrors[n])) {
                traceInfo(worker, "");
                return true;
            }
        }

        // Not an error
        return false;
    }


    /**
     *
     * @param
     * @throws Exceptions
     */
    private void print_line_wait_oper(NedWorker worker, String line, int timeout)
        throws SSHSessionException, IOException, NedException {
        resetTimeout(worker, timeout, 0);
        print_line_wait_oper0(worker, line, timeout);
        setReadTimeout(worker);
    }


    /**
     *
     * @param
     * @throws Exception
     */
    private void print_line_wait_oper(NedWorker worker, String line)
        throws IOException, SSHSessionException, NedException {
        print_line_wait_oper0(worker, line, 0);
    }


    /**
     *
     * @param
     * @throws Exceptions
     */
    private void print_line_wait_oper0(NedWorker worker, String line, int timeout)
        throws IOException, SSHSessionException, NedException {

        Pattern[] operPrompt = new Pattern[] {
            Pattern.compile("if your config is large. Confirm\\?[ ]?\\[y/n\\]\\[confirm\\]"),
            Pattern.compile("Do you wish to proceed with this commit anyway\\?[ ]?\\[no\\]"),
            Pattern.compile(PROMPT),
            Pattern.compile(CONFIG_PROMPT)
        };

        for (int retry = writeOobExclusiveRetries; retry >= 0; retry--) {

            // Send line to device and wait for echo
            traceVerbose(worker, "Sending(oper) '"+line+"'");
            session.print(line+"\n");
            session.expect(new String[] { Pattern.quote(line) }, worker);

            // Wait for (confirmation prompt)
            NedExpectResult res;
            if (timeout != 0) {
                res = session.expect(operPrompt, false, timeout, worker);
            } else {
                res = session.expect(operPrompt, worker);
            }

            // "if your config is large. Confirm?"
            if (res.getHit() == 0) {
                session.print("y");
                // Note: not echoing 'y'
                res = session.expect(operPrompt, worker);
            }

            // "Do you wish to proceed with this commit anyway?"
            else if (res.getHit() == 1) {

                // Answering 'no':
                if ("no".equals(commitOverrideChanges)) {
                    print_line_exec(worker, "no");
                    String msg0 = print_line_exec(worker, "show configuration history last 3");
                    String msg1 = print_line_exec(worker, "show configuration commit changes last 1");
                    throw new NedException(res.getText()+msg0+msg1);
                }

                // Answering 'yes':
                session.println("yes");
                session.expect(new String[] { "yes" }, worker);
                if (timeout != 0) {
                    res = session.expect(operPrompt, false, timeout, worker);
                } else {
                    res = session.expect(operPrompt, worker);
                }
                // Show the last commit changes in the trace for debugging
                print_line_exec(worker, "show configuration history last 5");
            }

            // Check device reply for error
            String reply = res.getText();
            if (!isCliError(worker, reply)) {
                // Command succeeded, exit loop
                break;
            }

            //
            // Command failed:
            //

            if (!line.trim().startsWith("commit")) {
                // abort | rollback
                if (reply.contains("Please use the command 'show configuration failed rollback")) {
                    // rollback failed
                    String msg = print_line_exec(worker, "show configuration failed rollback");
                    if (!msg.contains("No such configuration")) {
                        throw new NedException("\n'"+line+"' command failed:\n"+msg);
                    }
                }
                throw new NedException(reply);
            }

            //
            // commit [confirmed]
            //

            // Commit retries:
            if (retry > 0 && !reply.contains("Aborted:")) {
                int num = 1 + writeOobExclusiveRetries - retry;

                // Device is rebooting and not ready yet
                if (reply.contains("'try again' condition 'Try the operation again'")) {
                    sleep(worker, 1000 * (long)num, true);
                    continue;
                }

                // Check if we can retry commit:
                if (!reply.contains("Failed to commit ")
                    || reply.contains("Resource temporarily unavaila")
                    || reply.contains("Resource busy")
                    || reply.contains("This configuration has not been verified")
                    || reply.contains("took too long to respond to a startup request")
                    || reply.contains("you may try the 'commit' command again")) {

                    // Retry exceptions
                    if (reply.contains("Too many interfaces of this type")) {
                        throw new NedException(reply);
                    }

                    // Retry commit
                    sleep(worker, 1000, true);
                    traceInfo(worker, "Commit retry #"+num);
                    continue;
                }
            }

            // Return detailed error from 'show configuration failed [rollback]'
            if (reply.contains("issue") && reply.contains("show configuration failed")) {
                String msg = print_line_exec(worker, "show configuration failed");
                if (!msg.contains("No such configuration")) {
                    throw new NedException("\n'"+line+"' command failed:\n"+msg);
                }
            }

            // Throw error with output from commit command itself
            throw new NedException(reply);
        }
    }


    /**
     * Enter admin mode
     * @param
     * @throws Exceptions
     */
    protected boolean enterAdmin(NedWorker worker) throws IOException, SSHSessionException, NedException {

        if (adminLogin) {
            return true; // Permanently in admin mode
        }

        traceInfo(worker, "Entering admin mode");
        session.print("admin\n");
        session.expect(new String[] { "admin" }, worker);

        while (true) {
            traceVerbose(worker, "Waiting for reply (admin)");
            NedExpectResult res = session.expect(new String[] {
                    "Invalid input detected.*", // 0
                    "syntax error.*",
                    "This command is not authorized.*",
                    "Authentication failed.*",
                    "Command authorization failed.*",
                    "Incomplete command.*",
                    "Unable to login.*$",

                    "\\A.*[Aa]dmin [Uu]sername:", // 7
                    "\\A.*[Pp]assword:",
                    "cisco connected from.*",
                    "[Aa]dmin mode has been deprecated",
                    "\\A.*[a-zA-Z0-9][^\\# ]+#[ ]*$" // PROMPT
                }, worker);
            switch (res.getHit()) {
            case 7:
                traceVerbose(worker, "Sending admin name");
                if (connAdminName == null) {
                    throw new NedException("Failed to enter admin mode, missing 'connection admin name' ned-setting");
                }
                session.println(connAdminName);
                session.expect(new String[] { Pattern.quote(connAdminName) }, worker);
                break;
            case 8:
                String password = connAdminPassword;
                if (password == null) {
                    throw new NedException("Failed to enter admin mode, "
                                           +"missing 'connection admin password' ned-setting");
                }
                password = maapiDecrypt(connAdminPassword);
                traceVerbose(worker, "Sending admin password");
                session.setTracer(null);
                session.println(password);
                if (trace) {
                    session.setTracer(worker);
                }
                break;
            case 9:
                // Ignore 'cisco connected from '
                break;
            case 10:
                if (!iosversion.startsWith("7")) {
                    throw new NedException("Failed to enter admin mode: "+stringQuote(res.getText())
                                           +"\nSet ned-setting read/admin-show-running-config to false");
                }
                traceInfo(worker, "NOTICE: Failed to enter admin mode (deprecated in XR 7) - auto-disabling it");
                traceInfo(worker, "Waiting for prompt");
                session.expect(PROMPT, worker);
                this.readAdminShowRun = false;
                return false;
            case 11:
                traceInfo(worker, "Entered admin mode");
                return true;
            default:
                throw new NedException("Failed to enter admin mode: "+res.getText());
            }
        }
    }


    /**
     * Exit admin mode
     * @param
     * @throws Exceptions
     */
    protected void exitAdmin(NedWorker worker) throws IOException, SSHSessionException {
        if (adminLogin) {
            return;
        }

        traceVerbose(worker, "Exiting admin mode");
        print_line_exec(worker, "exit");
        traceInfo(worker, "Exited admin mode");

        // Restore terminal length due to XR bug [IOSXR-922 / RT40398]
        traceInfo(worker, "Restoring 0 terminal length after exiting admin mode due to CSCtk60033 XR bug");
        print_line_exec(worker, "terminal length 0");

        // Restore terminal width due to XR bug
        traceInfo(worker, "Restoring 0 terminal width after exiting admin mode due to XR bug");
        print_line_exec(worker, "terminal width 0");
    }


    /**
     * Enter config mode
     * @param
     * @throws Exceptions
     */
    protected void enterConfig(NedWorker worker) throws IOException, SSHSessionException, NedException {

        traceVerbose(worker, "Entering config mode");

        // 0 = (admin-config) | (config)
        // 1 = running configuration is inconsistent with persistent configuration
        // 2 = exec mode
        String line = "config " + configMethod;
        for (int retries = writeOobExclusiveRetries; retries >= 0; retries--) {
            session.print(line+"\n");
            NedExpectResult res = session.expect(ENTER_CONFIG_PATTERN, worker);
            if (res.getHit() == 0) {
                break;
            } else if (res.getHit() == 1 || retries == 0) {
                throw new NedException(line+": "+res.getText());
            }
            sleep(worker, 1000, true);
        }
        traceVerbose(worker, "Entered config mode");
        inConfig = true;
    }


    /**
     * Exit config mode
     * @param
     * @throws IOException, SSHSessionException, NedException
     */
    protected void exitConfig(NedWorker worker, String reason) throws IOException, SSHSessionException, NedException {

        traceVerbose(worker, "Exiting config mode ("+reason+")");
        if (!inConfig) {
            throw new NedException("Internal error, tried to exit non-config mode");
        }

        // Move to top config mode
        moveToTopConfig(worker);

        // Exit config mode
        traceVerbose(worker, "Sending end");
        session.print("end\n");
        session.expect(new String[] { "end" }, worker);

        NedExpectResult res = session.expect(EXIT_CONFIG_PATTERN, worker);
        switch (res.getHit()) {
        case 0:  // "Uncommitted changes found, commit them"
            session.print("no\n"); // Send a 'no'
            break;
        case 1:  // "You are exiting after a 'commit confirm'
            session.print("yes\n");
            break;
        case 2:  // Invalid input detected at
            throw new NedException("failed to exit config mode: "+res.getText());
        default: // exec mode
            break;
        }

        inConfig = false;
        traceVerbose(worker, "Exited config mode");
    }


    /**
     * Move to top config mode
     * @param
     * @throws NedException
     */
    private void moveToTopConfig(NedWorker worker) throws NedException {
        try {
            traceVerbose(worker, "Moving to top config mode");

            // (1) Send ENTER to check out current mode
            traceVerbose(worker, "Sending newline");
            session.print("\n");
            NedExpectResult res = session.expect(MOVE_TO_TOP_PATTERN);
            if (res.getHit() == 0) {
                return;
            }

            // (2) Send root to move to top mode
            traceVerbose(worker, "Sending 'root' to exit to top mode");
            session.print("root\n");
            session.expect(new String[] { "root" }, worker);
            // Note: root command prints 'Invalid input detected at' at top-mode
            res = session.expect(MOVE_TO_TOP_PATTERN);
            if (res.getHit() == 0) {
                return;
            }

            // (3) Root did not work, use exit command(s)
            for (int i = 0; i < 30; i++) {
                traceVerbose(worker, "Sending exit");
                session.print("exit\n");
                session.expect(new String[] { "exit" }, worker);
                res = session.expect(MOVE_TO_TOP_PATTERN, worker);
                switch (res.getHit()) {
                case 0: // (admin-config) | (config)
                    return;
                case 1: // Invalid input detected at
                    throw new NedException(res.getText());
                case 2: // config sub-mode
                    break;
                default:
                    throw new NedException("in exec mode");
                }
            }

            // (4) Failed
            throw new NedException("exit not accepted?");

        } catch (Exception e) {
            throw new NedException("failed to move to top mode: "+e.getMessage(), e);
        }
    }


    /**
     * Apply patch for CSCtk60033 XR bug (must delete class-maps in subsequent commit)
     * @param
     * @return
     */
    private String outputPatchCSCtk60033(NedWorker worker, String data, int fromTh) {
        StringBuilder sb = new StringBuilder("\n");
        String[] lines = data.split("\n");
        boolean patched = false;
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if (line.isEmpty()) {
                continue;
            }

            // no class-map */
            if (line.startsWith("no class-map ")) {

                // Create 'policy-map * / no class' line (note: may include 'type xxx')
                String classdel = line.replace("match-any ", "").replace("match-all ", "");
                classdel = classdel.replace("no class-map ", " no class ");

                // If deleted class-map also is deleted in policy-map, delete it in 2nd commit
                if (data.contains("\n"+classdel+"\n")) {

                    // Delay the delete of the class-map
                    traceDev(worker, "CSCtk60033 PATCH delayed '"+line+"'");
                    delayedCommit.append(line+"\n");
                    patched = true;

                    // Delete of policy-map must be in the same commit as delete of class-map (if referenced in class)
                    // Also delay delete of access-lists or may cause error on older XR (6.1.4)
                    String toptag = "";
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].isEmpty()) {
                            continue;
                        }
                        if (isTopExit(lines[i])) {
                            toptag = "";
                        } else if (Character.isLetter(lines[i].charAt(0))) {
                            toptag = lines[i];
                        }
                        if (toptag.startsWith("policy-map ") && lines[i].equals(classdel)) {
                            for (int p = n + 1; p < lines.length; p++) {
                                if (lines[p].equals("no "+toptag.trim())) {
                                    traceDev(worker, "CSCtk60033 PATCH delayed '"+lines[p]+"'");
                                    delayedCommit.append(lines[p]+"\n");
                                    lines[p] = "";
                                    i = lines.length;
                                    break;
                                }
                            }
                        }
                    }
                    continue;
                }
            }

            // Default, add line to first commit
            sb.append(line+"\n");
        }

        if (patched) {
            traceInfo(worker, "transformed => applied CSCtk60033 PATCH, deleting class-maps in separate commit");

            // (1) Also add all policy-map deletes in delayed commit
            if (autoCSCtk60033Patch2) {
                traceInfo(worker, "transformed => applied CSCtk60033-2 PATCH, deleting policy-maps in separate commit");
                lines = sb.toString().split("\n");
                sb = new StringBuilder("\n");
                for (int n = 0; n < lines.length; n++) {
                    String line = lines[n];
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (line.startsWith("no policy-map ")) {
                        traceDev(worker, "CSCtk60033-2 PATCH delayed '"+line+"'");
                        delayedCommit.append(line+"\n");
                    } else {
                        sb.append(line+"\n");
                    }
                }
            }

            // (2) Secondly we need to default auto-deleted policy-map's to avoid commit failure in 1st commit
            if (isDevice()) {
                lines = delayedCommit.toString().split("\n");
                for (int n = 0; n < lines.length; n++) {
                    if (lines[n].startsWith("no policy-map")
                        && policyMapIsEmpty(worker, lines[n].substring(3), fromTh, sb)) {
                        traceVerbose(worker, "transformed => applied CSCtk60033 PATCH, injecting default "
                                     +lines[n].substring(3));
                        sb.append("default "+lines[n].substring(3)+"\n");
                    }
                }
            }
            data = sb.toString();

            // (3) Finally we also need to move delete of access-lists to delayed commit on older XR (e.g. 6.1.4)
            if (autoCSCtk60033Patch2) {
                lines = data.split("\n");
                sb = new StringBuilder("\n");
                for (int n = 0; n < lines.length; n++) {
                    if (lines[n].startsWith("no ipv4 access-list ") || lines[n].startsWith("no ipv6 access-list ")) {
                        traceDev(worker, "CSCtk60033-2 PATCH delayed '"+lines[n]+"'");
                        delayedCommit.append(lines[n]+"\n");
                    } else {
                        sb.append(lines[n]+"\n");
                    }
                }
                data = sb.toString();
            }

            // Trace delayed commit
            traceInfo(worker, "Delayed commit:\n"+delayedCommit.toString());
        }

        // Done
        return data;
    }


    /**
     * Check if policy-map is empty in CDB for CSCtk60033 patch
     * @param
     * @return
     */
    private boolean policyMapIsEmpty(NedWorker worker, String polmap, int th, StringBuilder sb) {

        // Set CDB config path
        String path;
        String name;
        if (polmap.contains("policy-map type control ")
            && (name = getMatch(polmap, "policy-map type control (.+)")) != null) {
            path = confPath + "policy-map-event-control/policy-map{"+name+"}";
        } else if (polmap.contains("policy-map type ")
                   && (name = getMatch(polmap, "policy-map type \\S+ (.+)")) != null) {
            path = confPath + "policy-map{"+name+"}";
        } else {
            path = confPath + polmap.trim().replaceFirst(" ", "{") + "}";
        }
        traceVerbose(worker, "POL-MAP: '"+polmap+"' path = "+path);

        // Read policy-map config
        String config = maapiGetConfig(worker, th, path, 0);
        if (config == null) {
            return false;
        }
        String[] from = config.split("\n");
        String[] to = sb.toString().split("\n");

        // Loop through changes and remove deleted config in policy-map
        for (int t = 0; t < to.length; t++) {
            if (!to[t].startsWith(polmap)) {
                continue;
            }
            // policy-map *
            for (t = t + 1; t < to.length; t++) {
                if (to[t].equals(" end-policy-map")) {
                    break;
                }
                if (to[t].startsWith(" no class ")) {
                    String cm = to[t].replaceFirst(" no", "");
                    for (int f = 1; f < from.length; f++) {
                        if (from[f].startsWith(cm)) {
                            from[f] = "";
                            for (f = f + 1; f < from.length; f++) {
                                if (from[f].equals(" !")) {
                                    from[f] = "";
                                    f = from.length; // break out of outer loop
                                    break;
                                }
                                from[f] = "";
                            }
                        }
                    }
                }
            }
        }

        // Trim description and empty class-default
        for (int f = 1; f < from.length - 1; f++) {
            if (from[f].isEmpty()) {
                continue;
            } else if (from[f].startsWith(" description ")) {
                from[f] = "";
            } else if (from[f].equals(" class default") && from[f+1].equals(" !")) {
                from[f] = "";
                from[f+1] = "";
            }
        }
        config = linesToString(from);
        traceVerbose(worker, "FROM REMAINING="+config);

        // Check if empty, looks like this:
        // policy-map <name>
        //  class class-default
        //  !
        //  end-policy-map
        // !
        from = config.trim().split("\n");
        if (from.length <= 5) {
            traceVerbose(worker, polmap+" :: is empty");
            return true;
        }

        traceVerbose(worker, polmap+" :: is non-empty:\n"+config);
        return false;
    }


    /**
     * Apply patch for AAA tacacs server bug (must delete aaa group server tacacs+ last)
     * @param
     * @return
     */
    private String outputPatchAAATacacs(NedWorker worker, String data) {
        StringBuilder sb = new StringBuilder("\n");
        String[] lines = data.split("\n");
        boolean patched = false;
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if ((line.startsWith("no tacacs-server host ")
                 && !data.contains("\n"+line.trim().substring(3)+"\n"))
                || line.startsWith("no vrf ")
                || line.startsWith("no aaa group server tacacs")) {
                delayedCommit.append(line+"\n");
                patched = true;
                continue;
            }
            sb.append(line+"\n");
        }
        if (patched) {
            traceInfo(worker, "transformed => applied aaa-tacacs PATCH, deleting aaa group server tacacs++ last");
        }
        return sb.toString();
    }


    /**
     * Edit list to string builder
     * @param
     * @return
     * @throws NedException
     */
    private String editListToStringBuilder(NedWorker worker, String path, int th, StringBuilder sb)
        throws NedException {

        String name = path.replace(confPath, "");
        traceVerbose(worker, "EDIT: path = "+name);

        try {
            // Verify list exists
            if (!maapi.exists(th, path)) {
                traceVerbose(worker, "EDIT: '"+name+"' does not exist");
                return "";
            }

            // Read number of instances
            int num = maapi.getNumberOfInstances(th, path + "/line");
            traceVerbose(worker, "'"+name+"' getNumberOfInstances() = "+num);
            if (num <= 0) {
                traceInfo(worker, "EDIT: '"+name+"' is empty");
                return "";
            }

            // Bulk-read all lines
            MaapiCursor cr = maapi.newCursor(th, path + "/line");
            List<ConfObject[]> list = maapi.getObjects(cr, 2, num);

            // Add all the lines
            traceVerbose(worker, "EDIT: '"+name+"' = "+list.size()+" line(s)");
            StringBuilder lineno = new StringBuilder();
            for (int n = 0; n < list.size(); n++) {
                ConfObject[] objs = list.get(n);
                lineno.append(" " + objs[0].toString().trim());
                sb.append(objs[1].toString().trim()+"\n");
            }

            // Return line number 'list'
            return lineno.toString();

        } catch (Exception e) {
            throw new NedException("EDIT: editListToStringBuilder ERROR : "+e.getMessage(), e);
        }
    }


    /**
     * Set list to string builder
     * @param
     * @return
     * @throws NedException
     */
    private int setListToStringBuilder(NedWorker worker, String path, int th, StringBuilder sb)
        throws NedException {

        String name = path.replace(confPath, "");
        traceVerbose(worker, "SET: path = "+name);

        try {
            // Verify list exists
            if (!maapi.exists(th, path)) {
                traceVerbose(worker, "SET: '"+name+"' does not exist");
                return 0;
            }

            // Read number of instances
            int num = maapi.getNumberOfInstances(th, path + "/set");
            traceVerbose(worker, "'"+name+"' getNumberOfInstances() = "+num);
            if (num <= 0) {
                traceInfo(worker, "SET: '"+name+"' is empty");
                return 0;
            }

            // Bulk-read all lines
            MaapiCursor cr = maapi.newCursor(th, path + "/set");
            List<ConfObject[]> list = maapi.getObjects(cr, 2, num);

            // Add all the lines
            traceVerbose(worker, "SET: '"+name+"' = "+list.size()+" line(s)");
            int s = 0;
            for (int n = 0; n < list.size(); n++) {
                ConfObject[] objs = list.get(n);
                String value = objs[0].toString().trim();
                if (value.contains("ios-regex \"")) {
                    value = value.replaceAll("ios-regex \\\"(.*)\\\"", "ios-regex $1");
                }
                if (s + 1 < list.size()) {
                    sb.append(" "+value+",\n");
                } else {
                    sb.append(" "+value+"\n");
                }
                s++;
            }
            return s;

        } catch (Exception e) {
            throw new NedException("SET: setListToStringBuilder ERROR : "+e.getMessage(), e);
        }
    }


    /**
     * Inject data
     * @param
     * @return
     * @throws NedException
     */
    private String injectOutputData(NedWorker worker, String data, String[] entry, String dir) throws NedException {
        Pattern pattern = Pattern.compile(entry[1]+"(?:[\r])?[\n]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(data);
        int offset = 0;
        String[] groups = null;
        String insert;

        // before-first
        if (entry[3].equals("before-first")) {
            if (matcher.find()) {
                insert = fillInjectLine(worker, entry[2] + "\n", entry[3], fillGroups(matcher), dir);
                data = data.substring(0, matcher.start(0))
                    + insert
                    + data.substring(matcher.start(0));
            }
        }

        // before-each
        else if (entry[3].equals("before-each")) {
            while (matcher.find()) {
                insert = fillInjectLine(worker, entry[2] + "\n", entry[3], fillGroups(matcher), dir);
                data = data.substring(0, matcher.start(0) + offset)
                    + insert
                    + data.substring(matcher.start(0) + offset);
                offset = offset + insert.length();
            }
        }

        // after-last
        else if (entry[3].equals("after-last")) {
            int end = -1;
            while (matcher.find()) {
                end = matcher.end(0);
                groups = fillGroups(matcher);
            }
            if (end != -1) {
                insert = fillInjectLine(worker, entry[2] + "\n", entry[3], groups, dir);
                data = data.substring(0, end)
                    + insert + "\n"
                    + data.substring(end);
            }
        }

        // after-each
        else if (entry[3].equals("after-each")) {
            while (matcher.find()) {
                insert = fillInjectLine(worker, entry[2] + "\n", entry[3], fillGroups(matcher), dir) + "\n";
                data = data.substring(0, matcher.end(0) + offset)
                    + insert
                    + data.substring(matcher.end(0) + offset);
                offset = offset + insert.length();
            }
        }
        return data;
    }


    /**
     *
     * @param
     * @return
     */
    private String fillInjectLine(NedWorker worker, String insert, String where, String[] groups, String dir) {
        int offset = 0;

        // Replace $i with group value from match.
        // Note: hard coded to only support up to $9
        for (int i = insert.indexOf('$'); i >= 0; i = insert.indexOf('$', i+offset)) {
            int num = (insert.charAt(i+1) - '0');
            insert = insert.substring(0,i) + groups[num] + insert.substring(i+2);
            offset = offset + groups[num].length() - 2;
        }

        traceInfo(worker, "transformed "+dir+" injected "+stringQuote(insert)+" "+where+" "+stringQuote(groups[0]));

        return insert;
    }


    /**
     * Upload config to device using SFTP
     * @param
     * @throws Exceptions
     */
    private void sftpPutConfig(NedWorker worker, String data)
        throws NedException, IOException, SSHSessionException, NedException {
        final long start = tick(0);

        traceInfo(worker, "BEGIN sending (SFTP)");

        if (remoteConnection != null) {
            throw new NedException("SFTP apply ERROR: Using sftp to apply config is not supported via proxy");
        }
        if (writeDeviceFile == null || writeDeviceFile.isEmpty()) {
            throw new NedException("SFTP apply ERROR: no file name configured");
        }

        // Delete previous commit file (ignore errors)
        print_line_exec(worker, "do delete /noprompt "+writeDeviceFile);

        // Modify data
        data = stripLineAll(worker, data, "!", "=>", false);

        traceVerbose(worker, "SFTP_APPLY=\n"+stringQuote(data));

        // Connect SFTP and transfer file
        NedSSHClient client = null;
        try {
            client = sftpConnect(worker);
            NedSSHClient.SecureFileTransfer sftp = client.createSFTP();
            traceInfo(worker, "SFTP uploading file: "+writeDeviceFile+" ("+data.length()+" bytes)");
            sftp.put(data, writeDeviceFile, 0644);
            traceInfo(worker, "SFTP uploaded file ("+data.length()+" bytes)");
        } catch (Exception e) {
            throw new NedException("SFTP apply ERROR : " + e.getMessage());
        } finally {
            if (client != null) {
                client.close();
            }
        }

        // Load config to candidate
        traceVerbose(worker, "Loading config to candidate");
        String res = print_line_exec(worker, "load "+writeDeviceFile);

        // Check for errors
        if (res.contains("Couldn't open file")) {
            throw new NedException("SFTP load: "+res);
        }
        if (res.contains("Syntax/Authorization errors in one or more commands")) {
            res = print_line_exec(worker, "show configuration failed load");
            throw new NedException("SFTP apply: "+res);
        }

        traceInfo(worker, "DONE sending (SFTP) "+tickToString(start));
    }


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
        traceInfo(worker, "BEGIN COMMIT");

        // Commit
        doCommit(worker, true);

        // Commit response
        traceInfo(worker, "DONE COMMIT "+tickToString(start));
        worker.commitResponse();
    }


    /**
     * Do the commit
     * @param
     * @throws Exception
     */
    private void doCommit(NedWorker worker, boolean allowTrial) throws Exception {
        boolean pendingTrial = false;
        NedProgress.Progress progress = reportProgressStart(this, NedProgress.COMMIT_CONFIG);
        try {

            // Must be in config mode or nothing to commit
            if (!inConfig) {
                reportProgressStop(progress, NedProgress.ERROR);
                return;
            }

            //
            // Commit
            //
            traceInfo(worker, "Committing ("+commitMethod+") [num-commit "+numCommit+" "
                      +numAdminCommit+"a delayed="+delayedCommit.length()+"]");

            // Optional trial commit
            if (allowTrial && "confirmed".equals(commitMethod)) {
                String cmd = commitCommand + " confirmed " + commitConfirmedTimeout;
                if (cmd.contains(" show-error")) {
                    // show-error must be last in line
                    cmd = cmd.replace(" show-error", "") + " show-error";
                }
                print_line_wait_oper(worker, cmd, writeTimeout);
                pendingTrial = true;

                // Optional delay before confirming commit
                if (commitConfirmedDelay > 0) {
                    sleep(worker, commitConfirmedDelay, true);
                }
            }

            if ("before commit".equals(failphase)) {
                failphase = "";
                throw new NedException("COMMIT :: simulated Exception before commit");
            }

            // (confirm) commit
            print_line_wait_oper(worker, commitCommand, writeTimeout);
            pendingTrial = false;
            numCommit++;

            if ("after commit".equals(failphase)) {
                failphase = "";
                throw new NedException("COMMIT :: simulated Exception after commit");
            }

            // Send and commit delayedCommit
            if (delayedCommit.length() > 0) {
                String data = delayedCommit.toString();
                delayedCommit = new StringBuilder();

                traceInfo(worker, "Sending and committing "+data.length()+" bytes of delayed config:\n"+data);
                doSendConfig(worker, data.split("\n"));

                if ("before delayed".equals(failphase)) {
                    failphase = "";
                    throw new NedException("COMMIT :: simulated Exception before delayed commit");
                }

                print_line_wait_oper(worker, commitCommand, writeTimeout);
                numCommit++;

                if ("after delayed".equals(failphase)) {
                    failphase = "";
                    throw new NedException("COMMIT :: simulated Exception after delayed commit");
                }
            }

            // Exit config mode
            exitConfig(worker, "commit");

            // Restore terminal length due to modifying line length
            if (resetTerminal) {
                traceInfo(worker, "Restoring 0 terminal length after configuring line length");
                print_line_exec(worker, "terminal length 0");
                resetTerminal = false;
            }

            // Cache secrets
            if (secrets.needUpdate()) {
                traceInfo(worker, "[SECRETS] reading config");
                lastGetConfig = getConfig(worker, false);
                secrets.cache(worker, lastGetConfig);
            }

            // all-traps API
            handleAllTrapsOutput(worker);

            // Done
            reportProgressStop(progress);

        } catch (Exception e) {
            reportProgressStop(progress, NedProgress.ERROR);
            if (!session.serverSideClosed() && inConfig) {
                inConfig = false;
                session.print("abort\n");
                if (pendingTrial) {
                    abortPendingCommit(worker);
                }
            }
            throw e;
        }
    }


    /**
     * Called if '[no] snmp-server traps all-traps' was successfully sent to device
     * @param
     */
    private void handleAllTrapsOutput(NedWorker worker) throws Exception {
        if (apiSnmpServerEnableAllTraps == 0 || this.snmpAllTraps == 0) {
            return;
        }

        // create
        int length = 0;
        if (this.snmpAllTraps == 1) {
            traceInfo(worker, "all-traps: created X traps");

            // Set length to 1 and let it be updated in show().
            // Two reasons why:
            // (1) After commit it takes XR many seconds to populate show run
            //     with all the traps. A few are added each second.
            // (2) Downloading config using SFTP will show a different smaller
            //     number of traps for an obscure reason.
            length = 1;

            // Sleep 3 seconds to let at least some traps be shown
            sleep(worker, 3000, true);
        }

        // delete
        else if (this.snmpAllTraps == -1) {
            traceInfo(worker, "all-traps: deleted all traps");
        }

        final String path = this.operRoot + "/snmp-server-enable-num-traps";
        this.cdbOper.setElem(new ConfBuf(String.format("%d", length)), path);
    }


    /**
     * Called in show() to determine if 'snmp-server traps all-traps' should be injected
     * @param
     */
    private void handleAllTrapsInput(NedWorker worker, int numTrapsDev, StringBuilder sbin)
        throws ConfException, IOException, NedException {
        if (apiSnmpServerEnableAllTraps == 0 || numTrapsDev == 0) {
            return;
        }

        traceInfo(worker, "all-traps: found "+numTrapsDev+" 'snmp-server traps' entries");
        boolean injectAllTraps = false;

        // > 0 verify minimum number of traps
        if (apiSnmpServerEnableAllTraps > 0) {
            injectAllTraps = (numTrapsDev >= apiSnmpServerEnableAllTraps);
        }

        // < 0 verify maximum missing number of traps
        else {
            final String path = this.operRoot + "/snmp-server-enable-num-traps";
            String traps = operGetLeafString(worker, path);
            if (traps == null) {
                // missing value in oper-data
                // treat as no traps are set in order to be able to restore any missing
                traceInfo(worker, "all-traps: missing cdb entry, treat as traps are unset");
            } else {
                int numTrapsCdb = Integer.parseInt(traps);
                traceInfo(worker, "all-traps: in cdb = "+numTrapsCdb+"  on device = "+numTrapsDev
                          +"  max-diff = "+(-apiSnmpServerEnableAllTraps));
                if (numTrapsDev > numTrapsCdb) {
                    // Somewhat odd scenario, but let's raise the value in cdb to account for new trap(s)
                    // This will also allow service to delete oob-set traps if deleted in cdb (= 0)
                    traceInfo(worker, "all-traps: "+numTrapsCdb+" traps updated to "+numTrapsDev);
                    this.cdbOper.setElem(new ConfBuf(String.format("%d", numTrapsDev)), path);
                    injectAllTraps = true;
                } else {
                    injectAllTraps = (numTrapsCdb - numTrapsDev < -apiSnmpServerEnableAllTraps);
                }
            }
        }

        if (injectAllTraps) {
            traceInfo(worker, "all-traps: transformed <= injected 'snmp-server traps all-traps'");
            sbin.append("snmp-server traps all-traps\n");
        }
    }


    /**
     * Abort pending commit
     * @param
     */
    private void abortPendingCommit(NedWorker worker) {
        traceInfo(worker, "Abort triggered rollback of trial commit");
        setWriteTimeout(worker);
        try {
            traceInfo(worker, "Waiting for echo: 'abort'");
            session.expect("abort", worker);

            String echo = "Rolling back unconfirmed trial commit immediately";
            if (isNetsim()) {
                echo = "configuration rolled back";
            }
            Pattern[] prompt = new Pattern[] {
                Pattern.compile(echo),
                Pattern.compile(PROMPT)
            };
            while (true) {
                traceInfo(worker, "Waiting for device prompt or +'"+echo+"'");
                NedExpectResult res = session.expect(prompt, true, writeTimeout, worker);
                if (res.getHit() == 0) {
                    continue;
                }
                break;
            }
        } catch (Exception e) {
            logError(worker, "pending commit abort ERROR", e);
        }
    }


    /*
     **************************************************************************
     * persist
     **************************************************************************
     */

    /**
     * Persist (save) config on device
     * @param
     * @throws Exception
     */
    @Override
    public void persist(NedWorker worker) throws Exception {
        // No-op, XR saves config in commit
        worker.persistResponse();
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
    public void abort(NedWorker worker, String data) throws Exception {
        final long start = tick(0);
        if (trace) {
            session.setTracer(worker);
        }
        traceInfo(worker, "BEGIN ABORT ("+commitMethod+") [in-config="
                +inConfig+"] [num-commit "+numCommit+" "+numAdminCommit+"a]");

        doRollback(worker);

        traceInfo(worker, "DONE ABORT (rollbacked "+(numCommit+numAdminCommit)+" commit(s)) "+tickToString(start));
        worker.abortResponse();
    }


    /**
     * Rollback
     * @param
     * @throws Exception
     */
    private void doRollback(NedWorker worker) throws Exception {
        NedProgress.Progress progress = reportProgressStart(this, NedProgress.ROLLBACK_CONFIG);
        try {
            // If still in config mode, abort to drop the current commit
            if (inConfig) {
                traceInfo(worker, "Aborted uncommitted config");
                print_line_wait_oper(worker, "abort");
                inConfig = false;
            }

            // Rollback admin config commited in this session's prepare phase
            if (numAdminCommit > 0) {
                try {
                    traceInfo(worker, "Rollbacking last "+numAdminCommit+" admin commit(s)");
                    enterAdmin(worker);
                    String res = print_line_exec(worker, "rollback configuration last "+numAdminCommit, writeTimeout);
                    if (isExecError(res)) {
                        enterConfig(worker);
                        print_line_wait_oper(worker, "rollback configuration", writeTimeout);
                        exitConfig(worker, "rollback admin");
                    }
                    exitAdmin(worker);
                } finally {
                    numAdminCommit = 0;
                }
            }

            // If we have commited in this session, rollback
            if (numCommit > 0) {
                try {
                    traceInfo(worker, "Rollbacking last "+numCommit+" commit(s)");
                    if (isNetsim()) {
                        enterConfig(worker);
                        print_line_wait_oper(worker, "rollback configuration "+(numCommit-1), writeTimeout);
                        doCommit(worker, false);
                    } else {
                        print_line_wait_oper(worker, "rollback configuration last "+numCommit, writeTimeout);
                    }
                } finally {
                    numCommit = 0;
                }
            }

            // Done
            reportProgressStop(progress);

        } catch (Exception e) {
            reportProgressStop(progress, NedProgress.ERROR);
            throw e;
        }
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
            // NSO does not trace 'data' in REVERT like it does in PREPARE
            traceInfo(worker, "\n"+data);
        }
        traceInfo(worker, "BEGIN REVERT ("+commitMethod+") [in-config="
                +inConfig+"] [num-commit "+numCommit+" "+numAdminCommit+"a]");

        if (apiSnmpServerEnableAllTraps == 0
            && "rollback".equals(revertMethod)
            && (inConfig || numCommit > 0 || numAdminCommit > 0)) {
            // Use XR rollback command
            doRollback(worker);
        } else {
            // Apply the revert data in a new commit
            doApplyConfig(worker, data);
            doCommit(worker, false);
        }

        traceInfo(worker, "DONE REVERT (rollbacked "+(numCommit+numAdminCommit)+" commit(s)) "+tickToString(start));
        worker.revertResponse();
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
        StringBuilder reply = new StringBuilder();
        if ("show outformat raw".equals(cmd)) {
            reply.append("\nNext dry-run will show raw (unmodified) format.\n");
            showRaw = true;
        }

        // internal - fail <phase>
        else if (cmd.startsWith("fail ")) {
            failphase = cmd.substring(5).trim();
            reply.append("\nfailphase set to: '"+failphase+"'\n");
        }

        // internal - apply-delay <seconds>
        else if (cmd.startsWith("apply-delay ")) {
            applyDelay = Integer.parseInt(cmd.substring(12).trim());
            reply.append("\napply-delay set to: '"+applyDelay+"' seconds\n");
        }

        // internal - show ned-settings
        else if ("show ned-settings".equals(cmd)) {
            reply.append("\n"+nedSettings.dumpAll());
        }

        // internal - secrets resync
        else if ("secrets resync".equals(cmd)) {
            secrets.setResync(true);
            getConfig(worker, false);
            secrets.setResync(false);
            reply.append("\nRe-synced all cached secrets.\n");
        }

        // internal - secrets delete
        else if ("secrets delete".equals(cmd)) {
            try {
                secrets.delete(worker);
            } catch (Exception ignore) {
                // Ignore Exception
            }
            reply.append("\nDeleted cached secrets.\n");
        }

        // internal - sync-from-file <FILE>
        else if (isNetsim() && cmd.startsWith("sync-from-file ")) {
            syncFile = cmd.trim().substring(15).trim();
            reply.append("\nNext sync-from will use file = " + syncFile + "\n");
        }

        // internal - check-config-trace <trace file>
        else if (cmd.startsWith("check-config-trace ")) {
            final String originalIosModel = this.iosmodel;
            try {
                this.iosmodel ="ASR9K";
                this.offlineData = "COMMAND";
                final File file = new File(cmd.trim().substring(19).trim());
                int unknown = internalCmdCheckConfigTrace(worker, file, reply, null);
                if (unknown == 0) {
                    reply.append("\nNo unsupported config in "+cmd.trim().substring(19).trim()+"\n");
                }
            } finally {
                this.iosmodel = originalIosModel;
                this.offlineData = null;
            }
        }

        // internal - check-config-dir <directory>
        else if (cmd.startsWith("check-config-dir ")) {
            final String originalIosModel = this.iosmodel;
            try {
                this.iosmodel ="ASR9K";
                this.offlineData = "COMMAND";
                internalCmdCheckConfigDir(worker, cmd, reply, false);
            } finally {
                this.iosmodel = originalIosModel;
                this.offlineData = null;
            }
        }

        // internal - check-config-all <directory>
        else if (cmd.startsWith("check-config-all ")) {
            final String originalIosModel = this.iosmodel;
            try {
                this.iosmodel ="ASR9K";
                this.offlineData = "COMMAND";
                internalCmdCheckConfigDir(worker, cmd, reply, true);
            } finally {
                this.iosmodel = originalIosModel;
                this.offlineData = null;
            }
        }

        // Device command
        else {
            nedCommand.execute(worker, cmd);
            return;
        }

        // Usage|help
        if (reply.length() == 0) {
            reply.append("\n");
            reply.append("live-status exec any usage:\n");
            reply.append("  live-status exec any \"<device command> [option(s)]\"\n");
            reply.append("  live-status exec any-hidden \"<device command> [option(s)]\"\n");
            reply.append("  live-status exec any \"<internal command>\"\n");
            reply.append("Internal Commands:\n");
            reply.append("  apply-delay <seconds> - Set apply delay for debugging\n");
            reply.append("  check-config-trace <trace> - Check config from first show run in trace\n");
            reply.append("  check-config-dir <path> - Check all traces in a directory\n");
            reply.append("  check-config-all <path> - Concatenate all traces config and check\n");
            reply.append("  fail <phase> - internal test command to set fail phase\n");
            reply.append("  secrets resync - re-sync all cached secrets\n");
            reply.append("  secrets delete - delete all cached secrets\n");
            reply.append("  show ned-setting - show ned-settings\n");
            reply.append("  show outformat raw - Next commit dry-run will show raw format\n");
            reply.append("  sync-from-file <text file> - Sync config from file <text file>>\n");
        }

        // Internal command reply
        traceInfo(worker, "COMMAND - internal: "+stringQuote(cmd));
        traceInfo(worker, reply.toString());
        worker.commandResponse(new ConfXMLParam[]
            { new ConfXMLParamValue("cisco-ios-xr-stats", "result", new ConfBuf(reply.toString()))});
    }




    /**
     * Parse all .trace files in directory and return unknown config lines
     * @param
     * @throws Exception
     */
    private void internalCmdCheckConfigDir(NedWorker worker, String cmd, StringBuilder reply, boolean unique)
        throws Exception {
        final long start = tick(0);

        // List all .trace files in directory
        final String dir = cmd.trim().substring(17).trim();
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

        StringBuilder uniqueSb = null;
        if (unique) {
            uniqueSb = new StringBuilder();
        }

        // Loop through each .trace, read, extract first running config and turbo parse
        reply.append("Found "+files.length+" trace file(s) in "+dir+":\n");
        int unknownLines = 0;
        int unknownTraces = 0;
        for (int i = 0; i < files.length; i++) {
            try {
                int unknown = internalCmdCheckConfigTrace(worker, files[i], reply, uniqueSb);
                unknownLines += unknown;
                if (unknown > 0) {
                    unknownTraces++;
                }
            } catch (FileNotFoundException e) {
                traceInfo(worker, "ERROR READING TRACE: "+files[i].getName());
            }
        }

        if (uniqueSb != null) {
            unknownLines = parseConfig(worker, uniqueSb.toString(), reply, true);
            unknownTraces = 1;
            totalLines -= totalTraces;
        }

        reply.append("\nParsed "+totalTraces+" trace(s) containing "
                     +totalLines+" lines: "
                     +unknownLines+" unknown in "+unknownTraces+" file(s) "+tickToString(start)
                     + String.format(" (parse-time %d ms)", totalTime));
        if (this.totalFailed > 0) {
            reply.append("\nNotice: "+this.totalFailed+" trace(s) from unknown NED or missing configuration");
        }
        writeFile(reply.toString(), dir+"/cisco-iosxr-traces-config.txt");
    }


    /**
     * Parse a trace file and store unknown config lines in StringBuilder
     * @param
     * @throws Exception
     */
    private int internalCmdCheckConfigTrace(NedWorker worker, File file, StringBuilder reply, StringBuilder uniqueSb)
        throws Exception {
        String model = "unknown";
        String version = "unknown";

        final String name = file.getName();
        traceInfo(worker, "+++ Reading trace file: "+name);
        StringBuilder sb = new StringBuilder();
        Scanner myReader = new Scanner(file);
        try {
            while (myReader.hasNextLine()) {
                String line = myReader.nextLine();
                if (line.contains("- NED VERSION: ") && !line.contains(" cisco-iosxr ")) {
                    traceInfo(worker, "   Ignoring non cisco-iosxr NED trace: "+name);
                    this.totalFailed++;
                    return 0;
                }
                if (line.contains("- DEVICE VERSION ")) {
                    version = getMatch(line, "DEVICE VERSION[ ]+: (\\S+)");
                }
                if (line.contains("- DEVICE MODEL ")) {
                    model = getMatch(line, "DEVICE MODEL[ ]+: (\\S+)");
                }
                if (line.trim().endsWith(" SET_TIMEOUT")) {
                    continue;
                }
                sb.append(line+"\r\n");
            }
        } finally {
            myReader.close();
        }

        // Check if trace contains at least one running-config
        if (sb.indexOf("\nBuilding configuration") < 0) {
            reply.append("\n+++ "+name+":\n  Model: "+model+" Version: "
                         +version+"\n  INFO: Missing 'Building configuration'");
            return 0;
        }

        // Extract config from first running-config with unknown
        String dump = "";
        int start = -1;
        for (;;) {
            start = sb.indexOf("\n-- BEGIN SHOW", start + 1);
            if (start < 0) {
                break;
            }

            int end = sb.indexOf("\n-- DONE SHOW", start);
            if (end < 0) {
                reply.append("\n+++ "+name+":\n  Model: "+model+" Version: "
                             +version+"\n  INFO: Truncated trace, missing '-- DONE SHOW '\n");
                this.totalFailed++;
                return 0;
            }

            dump = sb.substring(start, end);
            if (getMatch(dump, "(Number of lines skipped[ ]+[:][ ]+0)") != null) {
                traceVerbose(worker, "    ignoring '0 unknown' configuration at start = "+start);
                continue; // Ignore dump with 0 unknown
            }

            end = dump.indexOf("\nend\r");
            if (end < 0) {
                end = dump.indexOf("\nend\n");
            }
            if (end > 0) {
                dump = dump.substring(0, end+5);
            }
            break;
        }

        // Trim admin mode config
        start = dump.lastIndexOf("-- Entering admin mode");
        if (start > 0) {
            dump = dump.substring(0, start);
        }

        traceDev(worker, "CHECK_AFTER:\n'''"+dump+"\n'''");

        // Trim and modify input config
        this.totalTraces++;
        String config = modifyInput(worker, trimInput(dump));

        // Append or check config
        if (uniqueSb != null) {
            uniqueSb.append("! PARSER_EXIT_TO_TOP\n");
            uniqueSb.append(config);
            return 0;
        } else {
            StringBuilder one = new StringBuilder();
            int unknown = parseConfig(worker, config, one, false);
            if (unknown > 0) {
                reply.append("\n+++ "+name+":\n  Model: "+model+" Version: "+version+"\n");
                reply.append(one);
            }
            return unknown;
        }
    }


    /**
     * Check config dump for unknown
     * @param
     * @return Return total unknown in this config dump
     * @throws Exception
     */
    private int parseConfig(NedWorker worker, String config, StringBuilder reply, boolean trimDuplicates)
        throws Exception {

        // Turbo parse running-config
        Schema.ParserContext pctx;
        String[] lines = config.split("\n");
        pctx = newParserContext(worker,Arrays.asList(lines),Schema.ParserDirection.FROM_DEVICE);
        pctx.parse();
        this.totalLines += lines.length;
        this.totalTime += pctx.parseTime;
        if (pctx.getFailCnt() <= 0) {
            return 0;
        }

        // Report unknown config using turbo-parser
        int unknown = 0;
        List<String> failureList = getParseFailureList(pctx, trimDuplicates);
        for (String line : failureList) {
            if (line.trim().startsWith("(line")) {
                unknown++;
            }
            reply.append(line+"\n");
        }

        return unknown;
    }


    /**
     * Exit live-status action prompting by sending CTRL-C until normal prompt.
     * @param
     * @throws Exception
     */
    protected boolean exitPrompting(NedWorker worker) throws IOException, SSHSessionException, NedException {

        traceInfo(worker, "Exiting from command prompt question");

        Pattern[] cmdPrompt = new Pattern[] {
            // Prompt patterns:
            Pattern.compile(PROMPT),
            Pattern.compile("\\A\\S*#"),
            Pattern.compile("The operation can no longer be aborted"),
            Pattern.compile("Enter 'abort' followed by RETURN to abort the operation"),

            // Question patterns:
            Pattern.compile(":\\s*$"),
            Pattern.compile("\\]\\s*$")
        };

        String lastText = "xyzXYZxyzXYZ";
        for (int n = 0; n < 100; n++) {
            traceVerbose(worker, "Sending CTRL-C");
            session.print("\u0003");
            traceVerbose(worker, "Waiting for non-question");
            NedExpectResult res = session.expect(cmdPrompt, true, readTimeout, worker);
            if (res.getHit() <= 1) {
                return true;
            } else if (res.getHit() == 2) {
                session.println("sync"); // confirm (sync/async)?
                return false;
            } else if (res.getHit() == 3) {
                session.println("abort"); // (admin) install
                return false;
            }
            if (lastText.equals(res.getText())) {
                traceVerbose(worker, "Aborting CTRL-C due to repetitive reply from device");
                session.println("");
                return false;
            }
            lastText = res.getText();
        }

        throw new NedException("exitPrompting :: Internal ERROR: failed to exit, report with raw trace");
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
    @Override
    protected void cleanup() throws Exception {
        traceInfo(null, "BEGIN CLEANUP");
        if (session != null) {
            // Logout from telnet terminal server
            if (adminLogin && proto.equals("telnet") && "serial".equals(remoteConnection)) {
                try {
                    session.println("exit");
                    session.expect("exit");
                    session.expect("[Uu]sername[:][ ]?");
                    session.print("\u00ff\u00f4");
                    session.close();
                    sleepMs(3000);
                } catch (Exception ignore) {
                    // Ignore Exception
                }
            }

            // Drop config mode
            else if (inConfig && !session.serverSideClosed()) {
                session.print("abort\n");
                inConfig = false;
            }
        }
        clearDataCache();
        traceInfo(null, "DONE CLEANUP");
    }


    /*
     **************************************************************************
     * didReconnect
     **************************************************************************
     */

    /**
     * Called when NSO reconnects to NED
     * @param
     * @throws
     */
    @Override
    protected void didReconnect(NedWorker worker) throws Exception {
        traceInfo(worker, "RECONNECTED - cleaning cached data");
        clearDataCache();
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
        traceInfo(worker, "BEGIN KEEP-ALIVE");
        boolean alive = true;
        try {
            if (session.serverSideClosed()) {
                reconnectDevice(worker);
            } else {
                traceVerbose(worker, "Sending newline");
                session.println("");
                traceVerbose(worker, "Waiting for prompt");
                session.expect(new String[] { PROMPT, CONFIG_PROMPT }, worker);
            }
        } catch (Exception e) {
            alive = false;
            logError(worker, "KEEP_ALIVE ERROR: "+e.getMessage(), e);
        }
        traceInfo(worker, "DONE KEEP-ALIVE = "+alive+" "+tickToString(start));
        return alive;
    }


    /**
     * Reconnect to device using connector
     * @throws Exception
     */
    protected void reconnectDevice(NedWorker worker) throws NedException {
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

        /* Maapi.encrypt patch - reverse logic to avoid diff on NETSIM
        if (isNetsim()) {
            if (secret.startsWith("clear $8$")) {
                return true; // Return true to allow NedSecrets to cache it
            }
            if (secret.startsWith("clear ")) {
                return false; // Return false to allow NedSecrets to replace it
            }
        }
        */

        // encrypted
        if (secret.matches("[0-9a-f]{2}(:([0-9a-f]){2})+")) {
            return false;   // aa:11 .. :22:bb
        }
        if (trimmed.startsWith("encrypted ") || trimmed.endsWith(" encrypted")) {
            return false;  // encrypted XXX || XXX encrypted
        }
        if (secret.startsWith("password ")) {
            return false;  // password XXX
        }
        if (secret.startsWith("password6 ")) {
            return false;  // password6 XXX
        }
        if (secret.startsWith("$1$")) {
            return false;  // $1$..
        }
        if (trimmed.endsWith(" 7")) {
            return false;   // XXX 7
        }
        if (getMatch(trimmed, "^([1-9] \\S+)") != null) {
            return false;  // [1-9] XXX
        }
        if (getMatch(trimmed, "^(10 \\S+)") != null) {
            return false;  // 10 XXX
        }

        // Default to cleartext
        return true;
    }


    /*
     **************************************************************************
     * Common utility methods
     **************************************************************************
     */

    /**
     * Set user session
     * @throws Exception
     */
    private void setUserSession(NedWorker worker) throws Exception {
        try {
            int usid = maapi.getMyUserSession();
            traceInfo(worker, "Maapi user session is "+usid);
        } catch (Exception ignore) {
            traceInfo(worker, "Maapi user session set to 1");
            maapi.setUserSession(1);
        }
    }


    /**
     * Attach to Maapi
     * @param
     *
     */
    private void maapiAttach(NedWorker worker, int fromTh, int toTh) throws NedException {
        try {
            maapi.attach(fromTh, 0);
            maapi.attach(toTh, 0);
            traceDev(worker, "Maapi.Attached: from="+fromTh+" to="+toTh);
        } catch (Exception e) {
            throw new NedException("Internal ERROR: maapiAttach()", e);
        }
    }


    /**
     * Detach from Maapi
     * @param
     *
     */
    private void maapiDetach(NedWorker worker, int fromTh, int toTh) {
        traceDev(worker, "Maapi.detach() from="+fromTh+" to="+toTh);
        if (fromTh > 0) {
            try {
                maapi.detach(fromTh);
            } catch (Exception e) {
                traceInfo(worker, "INFO: Maapi.detach(from) Exception: "+e.getMessage());
            }
        }
        if (toTh > 0) {
            try {
                maapi.detach(toTh);
            } catch (Exception e) {
                traceInfo(worker, "INFO: Maapi.detach(to) Exception: "+e.getMessage());
            }
        }
    }


    /**
     * Check if path exists
     * @param
     * @return
     * @throws NedException
     */
    protected boolean maapiExists(NedWorker worker, int th, String path) throws NedException {

        // Trim to absolute path
        path = MaapiUtils.normalizePath(path);

        try {
            if (maapi.exists(th, path)) {
                traceDev(worker, "maapiExists("+path+") = true");
                return true;
            }
        } catch (Exception e) {
            throw new NedException("maapiExists("+path+") ERROR : " + e.getMessage());
        }
        traceDev(worker, "maapiExists("+path+") = false");
        return false;
    }


    /**
     * Check if list exists (need to retrieve count or always true)
     * @param
     * @return
     */
    protected boolean maapiListExists(NedWorker worker, int th, String path) {
        path = MaapiUtils.normalizePath(path);
        try {
            if (!maapi.exists(th, path)) {
                traceDev(worker, "maapiListExists("+path+") = false");
                return false;
            }
            int num = maapi.getNumberOfInstances(th, path);
            traceDev(worker, "maapiListExists("+path+") = "+num);
            return (num > 0);
        } catch (Exception e) {
            logError(worker, "maapiListExists("+path+") ERROR: ", e);
        }
        traceDev(worker, "maapiListExists("+path+") = false");
        return false;
    }


    /**
     * Read config from CDB and output as CLI format
     * @param
     * @return
     */
    protected String maapiGetConfig(NedWorker worker, int th, String path, int trimLevel) {

        // Trim to absolute path
        path = MaapiUtils.normalizePath(path);

        // Load config from NSO CDB
        StringBuilder sb = new StringBuilder();
        try {
            if (!maapi.exists(th, path)) {
                return null;
            }

            MaapiInputStream in = maapi.saveConfig(th,
                                                   EnumSet.of(MaapiConfigFlag.MAAPI_CONFIG_C_IOS,
                                                              MaapiConfigFlag.CISCO_IOS_FORMAT),
                                                   path);
            if (in == null) {
                traceInfo(worker, "maapiGetConfig("+path+") ERROR: null config");
                return null;
            }

            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer, 0, buffer.length)) > 0) {
                sb.append(new String(buffer).substring(0, bytesRead));
                if (bytesRead < buffer.length) {
                    break;
                }
            }
        } catch (Exception e) {
            traceInfo(worker, "maapiGetConfig("+path+") ERROR: Exception "+ e.getMessage());
            return null;
        }

        String[] lines = sb.toString().split("\n");
        if (lines.length < 5) {
            traceVerbose(worker, "maapiGetConfig("+path+") length = "+lines.length);
            return null; // output does not contain 'devices device <device-id>\n config\n' + ' !\n!\n'
        }

        sb = new StringBuilder();
        for (int n = 2 + trimLevel; n < lines.length - 2 - trimLevel; n++) {
            String line = lines[n].substring(2);
            if (line.trim().startsWith(PFX) || line.trim().startsWith("no "+PFX)) {
                line = line.replaceFirst(PFX, "");
            }
            sb.append(line+"\n");
        }

        String data = sb.toString();
        traceDev(worker, "MAAPI_GET_AFTER=\n"+data);
        return data;
    }


    /**
     * Bulk read an entire list using maapi.getObjects()
     * @param
     * @return ArrayList with String[] containing all entries and all leaves for the entire list
     *         Note: Unset leaves are indicated by the null String, e.g. [i] = null
     *         Note2: For 'type empty' the name of the leaf is returned (excluding prefix)
     *         Warning: The list may not contain embedded lists.
     * @throws NedException
     */
    protected ArrayList<String[]> maapiGetObjects(NedWorker worker, int th, String path, int numLeaves)
        throws NedException {
        final long start = tick(0);
        try {
            ArrayList<String[]> list = new ArrayList<>();

            // Verify list exists
            if (!maapi.exists(th, path)) {
                traceVerbose(worker, "'" + path + "' not found");
                return list;
            }

            // Read number of instances
            int num = maapi.getNumberOfInstances(th, path);
            if (num <= 0) {
                traceVerbose(worker, "'" + path + "' is empty (" + num + ")");
                return list;
            }
            traceVerbose(worker, "'" + path + "' getNumberOfInstances() = " + num);

            // Bulk-read all rules
            MaapiCursor cr = maapi.newCursor(th, path);
            List<ConfObject[]> objList = maapi.getObjects(cr, numLeaves, num);

            // Add all the entries in an ArrayList
            for (int n = 0; n < objList.size(); n++) {
                ConfObject[] objs = objList.get(n);
                String[] entry = new String[numLeaves];
                // Add all the leaves in a String[] array
                for (int l = 0; l < numLeaves; l++) {
                    entry[l] = objs[l].toString();
                    if ("J_NOEXISTS".equals(entry[l])) {
                        entry[l] = null;
                    } else if (entry[l].startsWith(PFX)) {
                        entry[l] = entry[l].replaceFirst(PFX, "");
                    }
                    traceVerbose(worker, "LIST["+n+","+l+"] = "+entry[l]);
                }
                list.add(entry);
            }

            traceVerbose(worker, "'" + path + "' read " + numLeaves + " leaves in "
                         +objList.size()+" entries " + String.format("[%d ms]", tick(start)));
            return list;

        } catch (Exception e) {
            throw new NedException("Internal ERROR in maapiGetObjects(): " + e.getMessage(), e);
        }
    }


    /**
     * Read an entire list entry using maapi.getObject()
     * @param
     * @return String[] containing all the leaves for this list entry
     *         Note: Unset leaves are indicated by the null String, e.g. [i] = null
     *         Note2: For 'type empty' the name of the leaf is returned (excluding prefix)
     *         Warning: The list entry may not contain embedded lists.
     * @throws NedException
     */
    protected String[] maapiGetObject(NedWorker worker, int th, String path, int numLeaves)
        throws NedException {
        final long start = tick(0);
        try {
            // Verify list exists
            if (!maapi.exists(th, path)) {
                traceVerbose(worker, "'" + path + "' not found");
                return new String[0];
            }

            // Read the list entry and add all the leaves in a String[] array
            ConfObject[] obj = maapi.getObject(th, path);
            String[] entry = new String[numLeaves];
            for (int l = 0; l < numLeaves; l++) {
                entry[l] = obj[l].toString();
                if ("J_NOEXISTS".equals(entry[l])) {
                    entry[l] = null;
                } else if (entry[l].startsWith(PFX)) {
                    entry[l] = entry[l].replaceFirst(PFX, "");
                }
                traceVerbose(worker, "ENTRY["+l+"] = "+entry[l]);
            }

            traceVerbose(worker, "'" + path + "' read " + numLeaves + " leaves "
                         + String.format("[%d ms]", tick(start)));
            return entry;

        } catch (Exception e) {
            throw new NedException("Internal ERROR in maapiGetObjects(): " + e.getMessage(), e);
        }
    }


    /**
     * Get String leaf using Maapi
     * @param
     * @return
     */
    protected String maapiGetLeafString(NedWorker worker, int th, String path) {

        // Trim to absolute path
        path = MaapiUtils.normalizePath(path);

        // Get leaf
        try {
            if (maapi.exists(th, path)) {
                return ConfValue.getStringByValue(path, maapi.getElem(th, path));
            }
        } catch (Exception e) {
            traceInfo(worker, "maapiGetLeafString("+path+") Exception: "+e.getMessage());
        }
        return null;
    }


    /**
     * Get oper value from CDB
     * @param
     * @return
     */
    protected String operGetLeafString(NedWorker worker, String path) {

        // Trim to absolute path
        path = MaapiUtils.normalizePath(path);

        // Get leaf
        try {
            if (this.cdbOper.exists(path)) {
                ConfPath cp = new ConfPath(path);
                String value = this.cdbOper.getElem(cp).toString();
                traceDev(worker, "oper-data "+path+" = "+value);
                return value;
            }
        } catch (Exception e) {
            traceInfo(worker, "operGetLeafString("+path+") Exception: "+e.getMessage());
        }
        return null;
    }


    /**
     *
     * @param
     * @return
     * @throws IOException, SSHSessionException
     */
    private String print_line_exec(NedWorker worker, String line) throws IOException, SSHSessionException {

        // ned-setting cisco-iosxr developer simulate-command *
        String simulated = simulateCommand(worker, line);
        if (simulated != null) {
            return simulated;
        }

        // Send command and wait for echo
        session.print(line + "\n");
        session.expect(new String[] { Pattern.quote(line) }, worker);

        // Return command output
        traceDev(worker, "Waiting for '"+line+"' prompt");
        return session.expect(PROMPT, worker);
    }


    /**
     *
     * @param
     * @return
     * @throws IOException, SSHSessionException
     */
    private String print_line_exec(NedWorker worker, String line, int timeout)
        throws IOException, SSHSessionException {

        resetTimeout(worker, timeout, 0);

        // ned-setting cisco-iosxr developer simulate-command *
        String simulated = simulateCommand(worker, line);
        if (simulated != null) {
            return simulated;
        }

        // Send command and wait for echo
        session.print(line + "\n");
        session.expect(new String[] { Pattern.quote(line) }, worker);

        // Return command output
        traceDev(worker, "Waiting for prompt");
        return session.expect(PROMPT, timeout, worker);
    }


    /**
     *
     * @param
     * @return
     */
    protected boolean isExecError(String res) {
        return res.contains("Invalid input detected at")
            || res.contains("syntax error");
    }


    /**
     *
     * @param
     * @return
     */
    protected String simulateCommand(NedWorker worker, String line) {
        // ned-setting cisco-iosxr developer simulate-command *
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
            traceInfo(worker, "failed to simulate command "+stringQuote(line)+": "+e.getMessage());
        }
        return null;
    }


    /**
     * Read file from disk
     * @param
     * @return
     * @throws IOException
     */
    private String readFile(String file) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new java.io.FileReader(file));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line+"\r\n");
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }


    /**
     * Write file to disk
     * @param
     * @return
     */
    private boolean writeFile(String text, String file) {
        try (
             java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.FileWriter(file))
             ) {
            writer.write(text);
        } catch (java.io.IOException e) {
            return false;
        }
        return true;
    }


    /**
     * Check if line is top exit command
     * @param
     * @return
     */
    protected boolean isTopExit(String line) {
        line = line.replace("\r", "");
        if ("exit".equals(line)) {
            return true;
        }
        return "!".equals(line);
    }



    /**
     *
     * @param
     * @return
     */
    private String stripLineAll(NedWorker worker, String res, String search, String dir, boolean trim) {
        StringBuilder buffer = new StringBuilder();
        String[] lines = res.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (trim) {
                line = line.trim();
            }
            if (line.startsWith(search)) {
                traceVerbose(worker, "transformed "+dir+" stripped '"+line.trim()+"'");
                continue;

            }
            buffer.append(lines[i]+"\n");
        }
        return buffer.toString();
    }


    /**
     *
     * @param
     * @return
     */
    private boolean isDevice() {
        return !"NETSIM".equals(iosmodel);
    }


    /**
     *
     * @param
     * @return
     */
    protected String shortpath(String path) {
        path = path.substring(path.indexOf("/config/")+8);
        path = path.replaceFirst("cisco-ios-xr:", "");
        return path;
    }


    /**
     *
     * @param
     */
    private void sleep(NedWorker worker, long milliseconds, boolean log) {
        if (log) {
            traceInfo(worker, "Sleeping " + milliseconds + " milliseconds");
        }
        sleepMs(milliseconds);
        if (log) {
            traceInfo(worker, "Woke up from sleep");
        }
    }
    private void sleepMs(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    /**
     * Like NedString.stringDequote except that it preserves single backslash
     * @param
     * @return
     */
    private static String textDequote(String aText) {
        if (aText.startsWith("\"") && aText.endsWith("\"")) {
            aText = aText.substring(1,aText.length()-1); // Strip quotes around string
        }
        StringBuilder sb = new StringBuilder();
        StringCharacterIterator it = new StringCharacterIterator(aText);
        char c1 = it.current();
        while (c1 != CharacterIterator.DONE) {
            if (c1 == '\\') {
                char c2 = it.next();
                if (c2 == CharacterIterator.DONE) {
                    sb.append(c1);
                } else if (c2 == 'b') {
                    sb.append('\b');
                } else if (c2 == 'n') {
                    sb.append('\n');
                } else if (c2 == 'r') {
                    sb.append('\r');
                } else if (c2 == 'v') {
                    sb.append((char) 11); // \v
                } else if (c2 == 'f') {
                    sb.append('\f');
                } else if (c2 == 't') {
                    sb.append('\t');
                } else if (c2 == 'e') {
                    sb.append((char) 27); // \e
                } else if (c2 == '\\') {
                    sb.append('\\');
                } else if (c2 == '\"') {
                    sb.append('\"');
                } else {
                    sb.append(c1);
                    sb.append(c2);
                }
            } else {
                sb.append(c1);
            }
            c1 = it.next();
        }
        return sb.toString();
    }
}
