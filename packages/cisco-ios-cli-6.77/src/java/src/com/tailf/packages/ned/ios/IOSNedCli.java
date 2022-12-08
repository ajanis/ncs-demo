package com.tailf.packages.ned.ios;

import com.tailf.packages.ned.nedcom.Schema;
import com.tailf.packages.ned.nedcom.NedComCliBase;
import com.tailf.packages.ned.nedcom.MaapiUtils;
import com.tailf.packages.ned.nedcom.NedCommonLib;
import com.tailf.packages.ned.nedcom.NedCommonLib.NedState;
import com.tailf.packages.ned.nedcom.NedCommonLib.PlatformInfo;
import com.tailf.packages.ned.nedcom.NedDiff;
import com.tailf.packages.ned.nedcom.NedSecretCliExt;
import com.tailf.packages.ned.nedcom.NedSettings;
import com.tailf.packages.ned.nedcom.NedMetaData;
import com.tailf.packages.ned.nedcom.NsoInfo;
import com.tailf.packages.ned.nedcom.livestats.NedLiveStats;
import com.tailf.packages.ned.nedcom.livestats.NedLiveStatsException;
import com.tailf.packages.ned.nedcom.livestats.NedLiveStatsShowHandler;
import static com.tailf.packages.ned.nedcom.NedString.getMatch;
import static com.tailf.packages.ned.nedcom.NedString.getMatches;
import static com.tailf.packages.ned.nedcom.NedString.fillGroups;
import static com.tailf.packages.ned.nedcom.NedString.stringQuote;
import static com.tailf.packages.ned.nedcom.NedString.stringDequote;
import static com.tailf.packages.ned.nedcom.NedString.passwordQuote;
import static com.tailf.packages.ned.nedcom.NedString.passwordDequote;
import static com.tailf.packages.ned.nedcom.NedString.matcherToString;
import static com.tailf.packages.ned.nedcom.NedString.hasString;
import static com.tailf.packages.ned.nedcom.NedString.findString;
import com.tailf.packages.ned.nedcom.NedProgress;
import static com.tailf.packages.ned.nedcom.NedProgress.reportProgressStart;
import static com.tailf.packages.ned.nedcom.NedProgress.reportProgressStop;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import java.util.SortedMap;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Scanner;
import java.util.Iterator;

import java.text.StringCharacterIterator;
import java.text.CharacterIterator;

import java.net.InetAddress;
import java.net.NetworkInterface;

import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FilenameFilter;
import java.io.FileNotFoundException;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.SCPClient;
import ch.ethz.ssh2.SCPInputStream;

import com.tailf.conf.Conf;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfValue;
import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfException;
import com.tailf.conf.ConfXMLParam;
import com.tailf.conf.ConfXMLParamValue;

import com.tailf.maapi.Maapi;
import com.tailf.maapi.MaapiCrypto;
import com.tailf.maapi.MaapiCursor;
import com.tailf.maapi.MaapiException;
import com.tailf.maapi.MaapiConfigFlag;
import com.tailf.maapi.MaapiInputStream;

import com.tailf.ned.NedCmd;
import com.tailf.ned.NedExpectResult;
import com.tailf.ned.NedException;
import com.tailf.ned.NedMux;
import com.tailf.ned.NedWorker;
import com.tailf.ned.CliSession;
import com.tailf.ned.SSHSessionException;



/**
 * Implements the cisco-ios CLI NED
 * @author lbang
 *
 */
@SuppressWarnings("deprecation")
public class IOSNedCli extends NedComCliBase {

    // Constants
    private static final String PREFIX = "ios:";
    private static final String EXTENDED_PARSER = "extended-parser";
    private static final String META_DATA = "! meta-data :: ";
    private enum Echo { WAIT, DONTWAIT, TEXT }

    private static final String UNKNOWN = "unknown";
    private static final String NSKEY = "__key__";
    private static final int TIMEOUT_MOD = 10;

    // line X/a/m X/b/n
    private static final String LINE_RANGE3_REGEX =
        "\n(line (\\d+)/(\\d+)/(\\d+) \\2/(\\d+)/(\\d+))\r(.*?)?(?=\nline |\n!)";

    // Prompts

    // start of input, > 0 non-# and ' ', one #, >= 0 ' ', eol
    private static final String PRIVEXEC_PROMPT = "\\A[^\\# ]+#[ ]?$";
    private static final String PROMPT = "\\A\\S+#";
    private static final String CONFIG_PROMPT = "\\A\\S+\\(\\S+\\)#[ ]?$";
    private static final String CFG_PROMPT = "\\A.*\\(.*\\)#";
    private static final String ANY_PROMPT = "\\A\\S*#";

    // print_line_wait() pattern
    private static final Pattern[] PLW0 = new Pattern[] {
        // 0 prompts:
        Pattern.compile("\\A.*\\(cfg\\)#"),
        Pattern.compile("\\A.*\\(config\\)#"),
        Pattern.compile(CFG_PROMPT),
        Pattern.compile(ANY_PROMPT),
        // 4 y/n question:
        Pattern.compile("\\?[ ]{0,2}\\([Yy]/[Nn]\\)\\[[Yy].*"),  // ? (y/n[y)
        // 5 standard questions:
        Pattern.compile("\\?[ ]{0,2}\\(yes/\\[no\\]\\)"),  // ? (yes/[no])
        Pattern.compile("\\?[ ]{0,2}\\[[Yy]es/[Nn]o\\]"),  // ? [yes/no]
        Pattern.compile("\\?[ ]{0,2}\\[[Yy]es\\]"),        // ? [yes]
        Pattern.compile("\\?[ ]{0,2}\\[[Nn]o\\]"),         // ? [no]
        Pattern.compile("\\?[ ]{0,2}\\[confirm\\]"),       // ? [confirm]
        // 10 'key config-key password-encrypt' old password prompt
        Pattern.compile("\\AOld key: "),        // 'Old key:'
        // 11 Bad old password for 'key config-key password-encrypt'
        Pattern.compile("\\A.*Invalid key entered.*")      // %% Invalid key entered
        // 12 additional patterns from inject-answer
    };

    private static final Pattern[] ENTER_CONFIG_PATTERN = new Pattern[] {
        Pattern.compile("Do you want to kill that session and continue"),
        Pattern.compile("Configuration mode is locked by process.+Please try later"),
        Pattern.compile("\\A\\S*\\(config\\)#"),
        Pattern.compile(CFG_PROMPT),
        Pattern.compile("Turn config archive on before using Rollback Confirmed Change.*"),
        Pattern.compile("Aborted.*\n"),
        Pattern.compile("Error.*\n"),
        Pattern.compile("syntax error.*\n"),
        Pattern.compile("error:.*\n"),
        Pattern.compile("Config mode cannot be entered.*\n")
    };

    // NEDLIVESTATS prompts
    private static final Pattern[] NEDLIVESTATS_PROMPT = new Pattern[] {
        Pattern.compile(CFG_PROMPT),
        Pattern.compile("\\A[^\\# ]+#[ ]?$")
    };


    /**
     * Static warning exceptions, regular expressions. NOTE: Lowercase!
     */
    private static final String[] staticWarning = {
        // general
        "############################################################################.*",
        "warning[:,] \\S+.*",
        "warning:",
        ".?note:",
        "info:",
        "aaa: warning",
        "success",  //  " added successfully",
        "enter text message",
        "enter macro commands one per line",
        "this commmand is deprecated",
        "this command requires a reload to take effect",
        "will take effect after reload",
        "this command is an unreleased and unsupported feature",
        "cli will be deprecated soon",
        "command accepted but obsolete, unreleased or unsupported",
        "redundant .* statement",
        "elapsed time was \\d+ seconds",
        "configuring anyway",

        // remove || delete
        "hqm_tablemap_inform: class_remove error",
        "all rsa keys will be removed",
        "all router certs issued using these keys will also be removed",
        "not all config may be removed and may reappear after",
        "removed .* policy from .* interface",
        "this will remove previously",
        "removing ssp group",
        "remote  deleted",
        "tunnel interface was deleted",
        "mac address.*has been deleted from the bridge table",
        "non-fr-specific configuration, if not yet explicitly deconfigured",
        "entry does not exist", // no ip multicast vrf *
        "please remove .+ from all .+ for complete cleanup", // no platform mpls mtu-enable
        "deleting ac member of xconnect", // interface * / no service instance *
        " is disabled",

        // in case of some device flavors the entry is not deleted
        "\\S+ profile is removed",
        "service removed for domain .*",
        "will be removed from .* due to removal of",
        "warning: \\S+ is the default fax protocol, it can not be removed",
        "removed \\d+ (entry|entries)",
        "removing .+ configuration on all interfaces",
        "can't delete view \\S+",
        "can not find view \\S+",

        // change
        "changes to .* will not take effect until the next",
        "please reload the switch for .+ configuration to take effect", // stackwise-virtual
        "please reload the switch to disable .+ functionality", // no stackwise-virtual
        "security level for .* changed to",
        "connection name is changed",
        "changes to the running .* have been stored",
        "you are about to \\S+grade",
        "use 'write' command to make", // license boot level security
        "no change in the configuration",
        "same config is entered which has no effect",
        "a system reload is required before .+ change", // subscriber templating
        "changing media to \\S+", // interface * / media-type
        ".+ set to default configuration", // default
        "setting default value of \\S+", // no fabric timer
        "cannot disable \\S+ cef on this platform", // no ipv6 cef
        "login disabled on line",
        // platform hardware throughput crypto
        "the config will take effect on next reboot",
        "please write mem and reload",

        // VRF
        "removed due to \\S+abling vrf",
        "removed due to vrf change",
        "the static routes in vrf .*with outgoing interface .*will be",
        "ip.* addresses from all interfaces in vrf .*have been removed",
        "number of vrfs \\S+graded",
        "vrf .*exists but is not enabled",
        "a new tunnel id may be used if the default mdt is reconfigured for this vrf",
        "for vrf .* scheduled for deletion",
        "vrf \\S+ not configured, invalid vrf name",
        "unable to remove extended community", // ip vrf * / no route-target
        "vrf \\S+ is now bound to default vrf parameter map",
        "the current number of routes in the routing table is equal to, or exceeds", // ip vrf / maximum routes
        "the routing table is being reloaded to enforce", // ip vrf / maximum routes

        // vlan
        "vlan.* does not exist.* creating vlan",
        "please refer to documentation on configuring ieee 802.1q vlans",
        "vlan mapping is also changed",
        ".*vlan .* does not exist, creating vlan.*",
        "vlan mapping is also changed",
        "vlan  mod/ports",
        "applying vlan changes may take few minutes",
        "access vlan does not exist",
        "the .+ in slot \\S+ is currently offline",
        "pruning switched (off|on)", // no vtp pruning
        "stop tracking \\S+ tag \\S+", // no ip pim redundancy jonas

        // wlan | wireless
        "changing ap profile mapping may result in the rejoin of", // wireless tag site * / ap-profile
        "deleted policy profile may be associated to a wlan", // no wireless profile policy *
        "deleting \\S+ will cause associated aps to disconnect", // no wireless tag * *
        "associating \\S+ will cause associated ap to reconnect", // ap * / *-tag
        "may result in the rejoin of ap", // wireless tag site * / *
        "removing named tag mapping will result in the ap rejoin", // no ap *

        // interface
        "if .*interface does.* support baby giant frames",
        "no cef interface information",
        "unrecognized virtual interface .* treat it as loopback stub",
        "ipv4 and ipv6 addresses from all",
        "ip\\S+ addresses from all interfaces",
        "pim configuration for interface",
        "interface .* hsrp [a-f0-9:]* removed due to vrf change",
        "(\\S+): informational: \\S+ is in use on",
        "is reverting to router mode configuration, and remains disabled",
        "ospf will not operate on this interface until ip is configured on it",
        "command will have no effect with this interface",
        "portfast has been configured on ",  // spanning-tree portfast
        "creating a port-channel interface port-channel", // interface * / channel-group 3 mode active
        "speed auto-negotiation also needs to be set for auto-mdix to take effect", // mdix auto
        "the multilink group configuration will be removed from all the member links", // no interface Multilink
        "removal of channelized sonet/sdh interface configuration is not permitted", // no interface Serial
        "xconnect configuration on this circuit is incomplete", // interface * / xconnect
        "not found . using global defaults",
        "configured platform supported protocols",
        "is .+ fragmentation may occur", // interface * / ip mtu
        "loopback is a traffic-affecting operation", // interface * / loopback mac
        "policy attached to main[-]interface may not function as expected", // interface * / encapsulation ppp
        "macsec changed ip mtu on ", // interface * / macsec
        "the combination of icmp redirects and hsrp on the same", // interface * / ip redirects
        "interface may result in lost packets", // interface * / ip redirects
        "platform cannot disable ip route-cache on tunnel\\d+ interface", // interface * / no ip route-cache
        "shutdown is blocked in this state", // interface CEM* / no shutdown
        "bringing \\S+ the port due to config change", // interface * / no service instance *

        // controller * /
        "is out of range while validating payload for vcop", // cem-group *
        "has been configured by unframed channel", // no tug-2 * e1 * framing unframed

        // router
        "peer-group \\S+ is not present, but will go ahead and delete",
        "peergroups are automatically activated when parameters are configured",
        // router lisp / service * / encapsulation
        "setting the encapsulation of ipv(4|6) to \\S+. encapsulation cannot be different",
        "all bgp sessions must be reset to take the new", // bgp graceful-restart restart-time
        "only classful networks will be redistributed", // router ospf * / redistribute static
        "removing wide metrics also removes mpls te on", // router isis / no metric-style wide
        "reference bandwidth is changed", // router ospf / auto-cost reference-bandwidth
        ".* set use own .* address for the nexthop not supported", // MPLS-OUT
        "ospf supports only classless redistribution", // router ospf * / no redistribute isis
        // router bgp * / neighbor * send-label
        "for distributing mpls labels to ibgp peers, the update source should be set to",
        // router bgp * / address-family * / neighbor * advertise additional-paths best
        "this is a reminder that af level additional-path select",
        "all bgp sessions must be reset to take any \\S+ configurations", // no bgp ha-mode sso prefer

        // tunnel
        "tunnel mpls traffic-eng fast-reroute",
        "attach member tunnel to a master",
        "\\S+ tunnels are not enabled on this router", // interface * / mpls traffic-eng tunnels

        // mpls
        "pce disjoint-path source \\S+ type \\S+ group-id", // mpls traffic-eng lsp attributes * / pce
        "^record-route$", // mpls traffic-eng lsp attributes * / record-route
        "already set to default revision", // mpls oam / no echo revision
        "previously established ldp sessions will not be affected by this change", // no mpls ldp tcp pak-priority

        // AppNav / virtual-service
        "ac with local ip is being removed\\. service context cannot be enabled",
        "service context must have a appnav controller group attached to it before it can be enabled",
        "last vrf removed\\. disable this service-context",
        "activating virtual-service .* this might take a few minutes", //  virtual-service * / activate
        "virtual service .* install has not completed",
        "virtual service \\S+ was not activated",
        "acg must contain ip that is local to the device and interface should be up",

        // utd
        "please ensure .* is configured to use",
        "filtering will now be disabled on exiting the submode",
        "source db config has now been removed",
        "utd deregistered with appnav", // no utd
        "utd successfully registered with appnav", // utd engine standard multi-tenancy
        "utd redirect interface set to \\S+ internally", // utd engine standard multi-tenancy
        "utd appnav.*registration",  // utd engine standard multi-tenancy

        // SSH & certificate
        "enter the certificate",
        "certificate accepted",
        "certificate request sent",
        "ssh:publickey disabled.overriding rfc",
        "ssh:no auth method configured.incoming connection will be dropped",
        "please create rsa keys to enable ssh",
        "generating \\d+ bit rsa keys",   // ip http secure-server, crypto pki server * / no shutdown etc.
        "failed to generate persistent self-signed certificate", // ip http secure-server
        "the certificate has been deleted", // no certificate self-signed X
        "cannot delete certificate server certificates", // no crypto pki certificate chain

        // crypto
        "ikev2 \\S+ must have",
        "crypto-6-isakmp_on_off: isakmp is",
        "be sure to ask the ca administrator to revoke your certificates",
        "overriding already existing source with priority",
        "updated group cp to ", // crypto gkm group * / server address ipv4
        "this will remove all existing \\S+ on this map", // crypto map ** ipsec-isakmp / reverse-route static
        // crypto map ** ipsec-isakmp / no reverse-route static
        "removing \\S+ will delete all routes and clear current ipsec",
        "ikev2 proposal must either have a set of an encryption algorithm", // crypto ikev2 proposal *
        "event has been queued for processing", // crypto pki server * / shutdown
        "the .* change will take effect after existing .* expire", // crypto pki server * / lifetime
        "can't find policy \\S+",  // no crypto pki trustpoint
        "re-enter password:", // crypto pki server * / no shutdown
        "certificate server enabled", // crypto pki server * / no shutdown
        "cannot start the certificate server", // crypto pki server * / no shutdown
        "some server settings cannot be changed after", // crypto pki server * / no shutdown
        "removing rri will delete all routes and", // crypto map * / no reverse-route
        "remove the trustpoint to remove the cert chain", // no crypto pki certificate chain
        "ca trustpoint for cert chain not known", // no crypto pki certificate chain
        "enter a public key as a hexidecimal number", // crypto key pubkey-chain rsa / addressed-key * / key-string

        "enrollment url must be configured", // crypto pki trustpoint * / vrf
        "remember that, to permamently enforce", // no crypto ipsec optional
        "if cipher currently being used is not configured", // mka policy * / macsec-cipher-suite
        "active mka session on this interface cleared for ", // interface * / no mka pre-shared-key
        "certificate not found", // crypto pki certificate chain * / no certificate
        "policy already has same proposal set", // crypto ikev2 policy * / proposal default

        "fips key successfully set", // fips authorization-key
        "fips: authorization-key erased", // no fips authorization-key

        // nat
        "global .* will be port address translated",
        "outside interface address added",
        "pool nat-pool mask .* too small",
        "the active \\S+ status is not known",  // do clear ip nat translation vrf

        // policy-map & class-map
        "no specific protocol configured in class (.*) for inspection",
        "conform burst size \\S+creased to",
        "police .+ is adjusted to \\S+ to fit the interface supported range", // interface * / service-policy *
        "no service-policy attached to this interface", // interface * / no service-policy *
        "service policy .+ not attached", // interface * / no service-policy *
        "queueing must be removed from child classes", // policy-map * / class * / no shape average

        // routing
        "reload or use .* command, for this to take effect",
        // ip routing table .* does not exist. create first
        //no matching route to delete

        // queue | cos
        ".*propagating cos-map configuration to.*",
        ".*propagating queue-limit configuration to.*",
        "cos mutation map",
        "(cos-map|queue-limit) configured on all.* ports on slot",
        "please change queue-limit setting",
        "remove wrr queue cos map on all.* ports",

        // cable
        "minislot size set to", // no us-channel
        "the minislot size is now changed to",
        "response of applying upstream controller-profile", // no cable service class
        "fiber node \\d+ is valid",
        "port \\S+ admin change to (down|up)",
        "caution[:] .+ may result in .+",
        "\\S+ profile group \\S+ is reset to default",
        "setup depi class automatically", // cable rpd *
        "interface \\S+ ip \\S+ mask \\S+ removed", // cable oob / no virtual-arpd *
        "ptp domain \\d+ changed to default.+ of ", // ptp domain
        "channel.+ may only use sc qams .+ when max-carrier set to ", // no rf-chan 111 121
        " is(?: already)? enabled", // cable dynamic-punt enable
        "the profile is in use, the update will overwrite the legacy config in", // cable profile *
        "no .+ only apply in rollback", // interface Cable * / no cable managed fiber-node

        // call-home
        // call-home / profile * / destination transport-method http
        "profile cannot enable more than one transport method",
        // call-home / profile * / no destination transport-method http
        "call-home profile need to have at least one transport-method",
        //"removal of cisco tac profile is not allowed. it can be disabled by issuing", // call-home / no profile *
        "the email address configured in .* will be used as", // call-home / contact-email-addr
        "please configure .* under call-home mode", // call-home / profile * / no anonymous-reporting-only
        "the specified .* is removed", // call-home / no mail-server *
        "configuration succeed, but fail to parse the address", // call-home / mail-server *

        // snmp
        "user cannot belong to an auto-configured group",
        "trap generation suppressed",
        "however, configuration changes effective",

        // line * /
        "autoselect w/o the interface command .+ is useless",

        // misc
        "remove mapping of trigger id",
        "cts device id and password have been inserted in the local keystore", // cts credentials id [EXEC]
        "class set to .+ for redundancy group",  // redundancy / linecard-group * / class
        "restarting lc in slot .+ as it is being added as a secondary to", // redundancy / linecard-group * / member
        "warning\\S+ auto discovery already ", // service-insertion * / node-discovery enable
        "enabling mls qos globally",
        "name length exceeded the recommended length of .* characters",
        "a profile is deemed incomplete until it has .* statements",
        "address aliases with",
        "explicit path name",
        "global ethernet mtu is set to",
        "restarting .* service",
        "the .* command will also show the fingerprint",
        "encapsulation dot1q",
        "icmp redirect",
        "\\S+abling learning on",
        "\\S+abling failover",
        "the threshold option has been accepted",
        "added .*to the bridge table",
        " and mac table will be flushed",
        "arp inspection \\S+abled on",
        "configurations are no longer synchronized",
        "pix-[.]-",
        "secured .* cleared from",
        "current activity time is .* seconds",
        "rm entries aging is turned o",
        "zoning is currently not configured for interface",
        "propagating wred configuration to",
        "selected country",
        "is not a legal lat node name",
        "affinity \\S+ mask \\S+", // mpls traffic-eng lsp attributes * / affinity
        "changing vtp domain name from",
        "setting device to vtp .*",
        "wait for .* license request to succeed", // platform hardware throughput level
        "logging of %snmp-3-authfail is (dis|en)abled", // logging snmp-authfail
        "translating \\S+",  // ntp server
        "previously established ldp sessions may not have graceful restart protection", // mpls ldp graceful-restart
        "user configured would overwrite defaults", // parameter-map * / resolver
        "et-analytics destination .* combination does not exist", // et-analytics / no ip flow-export destination
        "delete policy map", // domain * / vrf * / no class
        "label range change will cause",
        "enabling .+ on sub interfaces will have unpredictable results", // cdp tlv-list * / port-id
        "enabled cdp on this interface,but cdp is not running globally", // interface * / cdp enable
        "please make sure \\S+ \\S+ is configured", // l2 vfi * / neighbor *
        "pseudowire configuration on this circuit is incomplete", // l2 vfi * / neighbor *
        "react configured but inactive for monitor rtp", // policy-map type performance-monitor * / class * / react *
        "domain id \\d+ config will take effect only", // switch virtual domain *
        "request to provisioning driver failed", // exit from 'radius server *'
        "cannot attach a card profile to the empty slot", // alarm-profile * attach card
        "port[-]range not supported for \\S+", // monitor session * type rspan-source / exit
        "probes are not configured", // ip sla group schedule
        "probes are already running", // ip sla group schedule * add
        "replication type not configured, configuration incomplete", // l2vpn evpn instance 10 vlan-based
        "ip multi-layer switching being disabled", // no mls rp ip
        "marking will work on the packet which comes from untrusted port", // mls qos protocol * precedence
        "reverting to default revision ", // mpls oam / no echo revision
        // ip igmp snooping querier
        "cannot be operationally enabled on some .+ because the required conditions have not been met",
        "the enable password you have chosen is the same as your enable secret",

        // reboot
        "need to be reboot to take effect", // hw-module system max-queue-limit
        "please reboot to activate this \\S+", // platform resource
        "warm reboot will be possible after the next power cycle or reload", // warm-reboot
        "configuration will be effective on reboot", // mls cef maximum-routes
        ".+ will take effect after reboot", // platform ipsec flexvpn-bypass-tcam

        // voice
        // dial-peer voice * / voice-class sip bind control source-interface
        "bind command will take effect after the bound interface is up",
        "all the dns have been removed", // call-manager-fallback / no max-dn
        "no resource, check voice card or dspfarm service is not configured", // dspfarm profile *
        "fac standard (is set|has been disabled)!", // telephony-service / fac
        "reload the system to remove ephone.*", // telephony-service / max-ephones
        "the ephone template tag has been changed under this ephone.*", // ephone * / ephone-template
        "skinny deleted entries for", // no telephony-service

        // vtp password
        ".*clearing device vtp password.*",
        ".*setting device vtp password to.*"
    };

    // Ignore message with delete command
    private static final String[] staticNoWarning = {
        "frequency value is less than timeout",
        "entry not configured",
        "identifier of this rpd should be configured first" // no cable rpd
    };

    // Error override messages (TRUE case)
    private static final String[] staticError = {
        "Error Message",
        "HARDWARE_NOT_SUPPORTED",
        " Incomplete command.",
        "password/key will be truncated to \\d+ characters",
        "Warning: Current config does not permit HSRP version 1",
        "Cannot modify internally generated ",
        "Warning: This command is no longer supported",
        "Error: Default ikev2 policy is disabled",
        "SNMP Manager not set" // cable modem remote-query
    };

    // PROTECTED:
    protected String confRoot;
    protected String operRoot;
    protected MaapiCrypto mCrypto = null;
    protected NedCommand nedCommand;
    protected long lastTimeout;
    protected boolean inConfig = false;
    protected boolean isDry = false;
    protected int lastToHandle = 0;
    protected String outputData;
    protected StringBuilder extInjectFirst;
    protected StringBuilder extInjectLast;
    protected HashMap<String,String> extBgpNbrRedeploy;
    protected List<String> extSentAction;
    protected int apiAclReseqRange; // ned-setting
    protected int snmpAllTraps = 0; // -1=delete, 1=created

    // devices info
    protected String iosmodel = UNKNOWN;
    private String iosname = "ios";
    private String iosversion = UNKNOWN;
    private String iosserial = UNKNOWN;
    private String xeversion = "";
    private String iospolice = UNKNOWN;
    private String licenseLevel = null;
    private String licenseType = null;
    private ArrayList<String[]> cachedShowInventory = new ArrayList<>();
    private String deviceProfile = "null";
    private int cdpDeviceType = 0; // 0=undetected, 1=default enabled, 2=default disabled

    // Utility classes
    private NedLocks locks;
    private NedDefaults defaults;
    private NedAcl nedAcl;
    private ConfigArchive configArchive;
    private ModeStack modeStack = new ModeStack();

    // nso info
    private String rollBackOctal;

    // Cached data (cleared in clearDataCache)
    private String lastTransformedConfig = null;
    private String lastTransactionId = null;
    private boolean ignoreNextWrite = false;
    private String lastPrompt = "";
    private boolean useRevert = false;
    private String oldConfigKey = null;
    private boolean sentConfigKey = false;

    // States
    private long lastReboot = 0;
    private Echo waitForEcho = Echo.WAIT;
    private String warningsBuf = "";
    private boolean showRaw = false;
    private String syncFile = null;
    private String offlineData = null;
    private String failphase = "";
    private StringBuilder relock = new StringBuilder();

    // have show command:
    private boolean haveShowBoot = true;
    private boolean haveShowVtpStatus = true;
    private boolean haveShowVlan = true;
    private boolean haveShowVlanSwitch = true;
    private boolean haveShowSnmpUser = true;
    private boolean haveShowSystemMtu = false;
    private boolean haveShowFipsKey = true;

    // NED-SETTINGS
    private ArrayList<String> dynamicWarning;
    private ArrayList<String[]> interfaceConfig;
    private ArrayList<String[]> injectConfig;
    private ArrayList<String[]> injectCommand;
    private ArrayList<String[]> injectAnswer;
    private ArrayList<String[]> replaceConfig;
    private ArrayList<String[]> replaceCommit;
    private String writeMemory;
    private String writeMemoryMode;
    private boolean writeTransferViaFile;
    private boolean writeIgnoreAbortErrors;
    private int applyRebootTimer = 0;
    private int configRevertTimer;
    private String policeFormat;
    private int deviceOutputDelay;
    private int configOutputMaxRetries;
    private long configOutputRetryInterval;
    private int chunkSize;
    private String transIdMethod;
    private String showRunningConfig;
    private boolean useIpMrouteCacheDistributed;
    private boolean newIpACL;
    protected boolean newAAAList;
    private String ipACLunorderedRegex;
    private boolean newSnmpServerHost;
    private boolean expandedLineVtyFormat;
    private boolean prettyLineVtyFormat;
    private boolean apiNewMlsQos;
    private boolean apiIosCommon;
    private int apiSnmpServerEnableAllTraps;
    private boolean resequenceACL;
    private boolean includeCachedShowVersion;
    private boolean includeCachedShowInventory;
    private boolean autoInterfaceSwitchportStatus;
    private boolean autoBgpNbrPasswordPatch;
    private boolean autoStackwiseVirtualPatch;
    private String autoCdpReadInject;
    private boolean autoIfRangeWrite;
    private boolean autoCompressSpanningTreeVlan;
    private String devPrepareDryModel;
    private int devFailIsAlive;
    private Pattern[] plw;
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
     * NED cisco-ios constructor
     */
    public IOSNedCli() {
        super();
    }


    /**
     * NED cisco-ios constructor
     * @param deviceId
     * @param mux
     * @param trace
     * @param worker
     */
    public IOSNedCli(String deviceId,
                     NedMux mux,
                     boolean trace,
                     NedWorker worker) throws Exception {
        super(deviceId, mux, trace, worker);
        this.confRoot = "/ncs:devices/ncs:device{"+deviceId+"}/config/ios:";
        this.operRoot = "/ncs:devices/ncs:device{"+deviceId+"}/ncs:ned-settings/ios-op:cisco-ios-oper";
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
    protected void setupParserContext(Schema.ParserContext parserContext) {
        NedWorker worker = (NedWorker)parserContext.externalContext;
        if (parserContext.parserDirection == Schema.ParserDirection.FROM_DEVICE) {

            traceVerbose(worker, "Adding /tailfned/police: "+iospolice);
            parserContext.addVirtualLeaf("/tailfned/police", iospolice);

            if (newIpACL) {
                traceVerbose(worker, "Adding /tailfned/api/new-ip-access-list");
                parserContext.addVirtualLeaf("/tailfned/api/new-ip-access-list", "");
            }
            if (newAAAList) {
                traceVerbose(worker, "Adding /tailfned/api/new-aaa-list-syntax");
                parserContext.addVirtualLeaf("/tailfned/api/new-aaa-list-syntax", "");
            }
            if (resequenceACL) {
                traceVerbose(worker, "Adding /tailfned/api/resequence-access-list");
                parserContext.addVirtualLeaf("/tailfned/api/resequence-access-list", "");
            }
            if (newSnmpServerHost) {
                traceVerbose(worker, "Adding /tailfned/api/new-snmp-server-host");
                parserContext.addVirtualLeaf("/tailfned/api/new-snmp-server-host", "");
            }
            if (expandedLineVtyFormat) {
                traceVerbose(worker, "Adding /tailfned/api/expanded-line-vty-format");
                parserContext.addVirtualLeaf("/tailfned/api/expanded-line-vty-format", "");
            }
            if (apiNewMlsQos) {
                traceVerbose(worker, "Adding /tailfned/api/new-mls-qos");
                parserContext.addVirtualLeaf("/tailfned/api/new-mls-qos", "");
            }
            if (apiSnmpServerEnableAllTraps != 0) {
                traceVerbose(worker, "Adding /tailfned/api/snmp-server-enable-all-traps");
                parserContext.addVirtualLeaf("/tailfned/api/snmp-server-enable-all-traps", "");
            }
        }
    }

    @Override
    protected void preParseNotify(NedWorker worker, Schema.ParserContext parserContext, NedState state) {
        if ((state == NedState.SHOW_PARTIAL) ||
            (parserContext.parserDirection == Schema.ParserDirection.TO_DEVICE)) {
            traceVerbose(worker, "Adding to-transaction data-provider for ned-settings api");
            addTransactionDataProvider(parserContext);
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
        try {
            // cisco-ios auto interface-switchport-status - FIRST because read by other settings
            autoInterfaceSwitchportStatus = nedSettings.getBoolean("auto/interface-switchport-status");

            //
            // read
            //
            transIdMethod = nedSettings.getString("read/transaction-id-method");
            showRunningConfig = nedSettings.getString("read/show-running-method");
            if (showRunningConfig.startsWith("scp-transfer") && proto != null && !"ssh".equals(proto)) {
                throw new NedException("ned-settings: must use CLI protocol ssh for read/show-running-method scp-transfer");
            }

            /*
             * read/replace-config
             */
            replaceConfig = new ArrayList<>();
            List<Map<String,String>> entries = nedSettings.getListEntries("read/replace-config");
            for (Map<String,String> entry : entries) {
                String[] newEntry = new String[4];
                newEntry[0] = entry.get(NSKEY); // "id"
                newEntry[1] = entry.get("regexp");
                newEntry[2] = entry.get("replacement");
                newEntry[3] = entry.get("when");
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
                if (newEntry[3] != null) {
                    buf += " " + newEntry[3];
                }
                traceVerbose(worker, buf);
                replaceConfig.add(newEntry);
            }

            /*
             * read/inject-config
             */
            injectConfig = new ArrayList<>();
            entries = nedSettings.getListEntries("read/inject-config");
            for (Map<String,String> entry : entries) {
                String[] newEntry = new String[4];
                newEntry[0] = entry.get(NSKEY); // "id"
                newEntry[1] = entry.get("regexp");
                newEntry[2] = entry.get("config");
                newEntry[3] = entry.get("where");
                String buf = "read/inject-config "+newEntry[0];
                if (newEntry[2] == null) {
                    throw new NedException("ned-settings: "+buf+" missing config");
                }
                if (newEntry[1] != null) {
                    buf += " regexp "+stringQuote(newEntry[1]);
                }
                buf += " cfg "+stringQuote(newEntry[2]);
                if (newEntry[3] != null) {
                    buf += " " + newEntry[3];
                }
                traceVerbose(worker, buf);
                injectConfig.add(newEntry);
            }

            /*
             * read/inject-interface-config
             */
            interfaceConfig = new ArrayList<>();

            // Add a static global default 'no switchport' setting
            // Used for devices/interfaces which do not support switchport
            // or for devices which hide 'no switchport' when disabled, eg:
            // WS-C6504-E or CISCO7606-S or CISCO2901/K9
            if (!autoInterfaceSwitchportStatus) {
                String[] staticEntry = new String[3];
                staticEntry[0] = "Ethernet|Port-channel";
                staticEntry[1] = "no switchport";
                staticEntry[2] = "globstat-sp";
                traceVerbose(worker, "read/inject-interface-config "+staticEntry[2]
                             +" if "+stringQuote(staticEntry[0])
                             +" cfg "+stringQuote(staticEntry[1]));
                interfaceConfig.add(staticEntry);
            }
            entries = nedSettings.getListEntries("read/inject-interface-config");
            for (Map<String,String> entry : entries) {
                String[] newEntry = new String[3];
                newEntry[0] = entry.get("interface");
                newEntry[1] = entry.get("config");
                newEntry[2] = entry.get(NSKEY); // "id"
                if (newEntry[0] == null || newEntry[1] == null) {
                    throw new NedException("ned-settings: read/inject-interface-config "+newEntry[2]
                                           +" missing interface or config");
                }
                traceVerbose(worker, "read/inject-interface-config "+newEntry[2]
                             +" if "+stringQuote(newEntry[0])
                             +" cfg "+stringQuote(newEntry[1]));
                interfaceConfig.add(newEntry);
            }

            //
            // write
            //
            writeMemory = nedSettings.getString("write/memory-method");
            writeMemoryMode = nedSettings.getString("write/memory-setting");
            configOutputMaxRetries = nedSettings.getInt("write/config-output-max-retries");
            configOutputRetryInterval = (long)nedSettings.getInt("write/config-output-retry-interval");
            chunkSize = nedSettings.getInt("write/number-of-lines-to-send-in-chunk");
            deviceOutputDelay = nedSettings.getInt("write/device-output-delay");
            writeTransferViaFile = nedSettings.getBoolean("write/transfer-via-file");
            configRevertTimer = nedSettings.getInt("write/config-revert-timer");
            if (configRevertTimer == 0) {
                // Ignore with 'write/configure-revert-timer' ned-setting
                applyRebootTimer = nedSettings.getInt("write/apply-reboot-timer");
            }
            writeIgnoreAbortErrors = nedSettings.getBoolean("write/ignore-abort-errors");

            /*
             * write/config-warning
             */
            dynamicWarning = new ArrayList<>();
            entries = nedSettings.getListEntries("write/config-warning");
            for (Map<String,String> entry : entries) {
                String key = entry.get(NSKEY);
                traceVerbose(worker, "write/config-warning "+key);
                dynamicWarning.add(stringDequote(key));
            }

            /*
             * write/replace-commit
             */
            replaceCommit = new ArrayList<>();
            entries = nedSettings.getListEntries("write/replace-commit");
            for (Map<String,String> entry : entries) {
                String[] newEntry = new String[4];
                newEntry[0] = entry.get(NSKEY); // "id"
                newEntry[1] = entry.get("regexp");
                newEntry[2] = entry.get("replacement");
                String buf = "write/replace-commit "+newEntry[0];
                buf += " regexp "+stringQuote(newEntry[1]);
                if (newEntry[1] == null) {
                    throw new NedException("ned-settings: write/replace-commit "+newEntry[0]+" missing regexp");
                }
                if (newEntry[2] != null) {
                    buf += " to "+stringQuote(newEntry[2]);
                } else {
                    newEntry[2] = "";
                    buf += " filtered";
                }
                traceVerbose(worker, buf);
                replaceCommit.add(newEntry);
            }

            /*
             * write/inject-command
             */
            injectCommand = new ArrayList<>();
            entries = nedSettings.getListEntries("write/inject-command");
            for (Map<String,String> entry : entries) {
                String[] newEntry = new String[4];
                newEntry[0] = entry.get(NSKEY); // "id"
                newEntry[1] = entry.get("config-line");
                if (newEntry[1] == null) {
                    newEntry[1] = "";
                }
                newEntry[2] = entry.get("command");
                newEntry[3] = entry.get("where");
                if (newEntry[3] == null) {
                    throw new NedException("ned-settings: write/inject-command "+newEntry[0]+" missing 'where'");
                }
                String buf = "write/inject-command "+newEntry[0]+" cfg "+stringQuote(newEntry[1]);
                if (newEntry[2] != null) {
                    buf += " cmd "+stringQuote(newEntry[2]);
                } else {
                    newEntry[2] = "";
                    buf += " filtered";
                }
                buf += " "+newEntry[3];
                traceVerbose(worker, buf);
                injectCommand.add(newEntry);
            }

            /*
             * write/inject-answer
             */
            injectAnswer = new ArrayList<>();
            entries = nedSettings.getListEntries("write/inject-answer");
            for (Map<String,String> entry : entries) {
                String[] newEntry = new String[4];
                newEntry[0] = entry.get(NSKEY); // "id"
                newEntry[1] = entry.get("question");
                newEntry[2] = entry.get("answer");
                newEntry[3] = entry.get("ml-question");
                if (newEntry[1] == null || newEntry[2] == null) {
                    throw new NedException("ned-settings: write/inject-answer "
                                           +newEntry[0]+" missing question or answer");
                }
                String buf = "write/inject-answer "+newEntry[0]
                    + " q " +stringQuote(newEntry[1])
                    + " a " +stringQuote(newEntry[2]);
                if (newEntry[3] != null) {
                    buf += " ml-q " +stringQuote(newEntry[3]);
                }
                traceVerbose(worker, buf);
                injectAnswer.add(newEntry);
            }

            // Create print_line_wait() pattern 'plw'
            plw = new Pattern[PLW0.length + injectAnswer.size()];
            for (int i = 0; i < PLW0.length; i++) {
                plw[i] = PLW0[i];
            }
            for (int i = 0; i < injectAnswer.size(); i++) {
                String[] entry = injectAnswer.get(i);
                plw[PLW0.length + i] = Pattern.compile(entry[1]);
            }

            //
            // auto
            //
            useIpMrouteCacheDistributed = nedSettings.getBoolean("auto/use-ip-mroute-cache-distributed");
            autoBgpNbrPasswordPatch = nedSettings.getBoolean("auto/bgp-nbr-password-patch");
            autoStackwiseVirtualPatch = nedSettings.getBoolean("auto/stackwise-virtual-if-indent-patch");
            autoCdpReadInject = nedSettings.getString("auto/cdp-read-inject");
            autoIfRangeWrite = nedSettings.getBoolean("auto/interface-range-write");
            autoCompressSpanningTreeVlan = nedSettings.getBoolean("auto/compress-spanning-tree-vlan");

            //
            // api
            //
            policeFormat = nedSettings.getString("api/police-format");
            if (policeFormat == null) {
                policeFormat = "auto";  // Note: leaf-list does not support default statement
            }
            newIpACL = nedSettings.getBoolean("api/new-ip-access-list");
            resequenceACL = nedSettings.getBoolean("api/access-list-resequence");
            apiAclReseqRange = nedSettings.getInt("api/acl-resequence-range");
            ipACLunorderedRegex = nedSettings.getString("api/unordered-ip-access-list-regex");
            newSnmpServerHost = nedSettings.getBoolean("api/new-snmp-server-host");
            expandedLineVtyFormat = nedSettings.getBoolean("api/expanded-line-vty-format");
            prettyLineVtyFormat = nedSettings.getBoolean("api/pretty-line-vty-format");
            apiNewMlsQos = nedSettings.getBoolean("api/new-mls-qos");
            apiIosCommon = nedSettings.getBoolean("api/ios-common");
            apiSnmpServerEnableAllTraps = nedSettings.getInt("api/snmp-server-enable-all-traps");
            newAAAList = nedSettings.getBoolean("api/new-aaa-list-syntax");

            // developer
            devPrepareDryModel = nedSettings.getString("developer/prepare-dry-model");
            failphase = nedSettings.getString("developer/failphase");
            devFailIsAlive = nedSettings.getInt("developer/fail/is-alive");

            //
            // deprecated
            //
            includeCachedShowVersion = nedSettings.getBoolean("deprecated/cached-show-enable/version");
            includeCachedShowInventory = nedSettings.getBoolean("deprecated/cached-show-enable/inventory");

            // write config-archive *
            configArchive = new ConfigArchive(this);
            configArchive.init(worker);

            traceInfo(worker, "DONE nedSettingsDidChange "+tickToString(start));

        } catch (Exception e) {
            throw new NedException("Failed to read ned-settings"+e.getMessage(), e);
        }
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
        try {
            // Set terminal settings (exit config mode if logged into it, e.g. serial terminal)
            final String disablePager = "terminal length 0";
            session.println(disablePager);
            session.expect(new String[] { Pattern.quote(disablePager) }, worker);
            NedExpectResult res = session.expect(new String[] { CONFIG_PROMPT, PRIVEXEC_PROMPT }, worker);
            if (res.getHit() == 0) {
                // Logged in directly in config mode, exit it and resend terminal command
                traceInfo(worker, "Logged into config mode, exiting and retrying terminal length");
                this.inConfig = true;
                exitConfig(worker, "probe");
                print_line_exec(worker, disablePager);
            }
            print_line_exec(worker, "terminal width 0");

            // Show version
            String version = print_line_exec(worker, "show version");
            version = version.replace("\r", "");

            // Verify that this is an IOS device
            traceInfo(worker, "Inspecting version string");
            if (!version.contains("Cisco IOS Software")
                && !version.contains("Cisco Internetwork Operating")
                && !version.contains("Cisco Wide Area Application Services Software")) {
                throw new NedException("Unknown device :: " + version);
            }

            // Found IOS device, init NED
            traceVerbose(worker, "Found IOS device");

            // NETSIM
            if (version.contains("NETSIM")) {
                this.iosmodel = "NETSIM";
                this.iosversion = "cisco-ios-" + NedMetaData.nedVersion;
                this.iosserial = device_id;

                // Show CONFD & NED version used by NETSIM in ned trace
                print_line_exec(worker, "show confd-state version");
                print_line_exec(worker, "show confd-state loaded-data-models data-model tailf-ned-cisco-ios");

                // Disable show commands for device only:
                traceInfo(worker, "Disabling all device show checks");
                haveShowBoot = haveShowVtpStatus = haveShowVlan = haveShowVlanSwitch = haveShowSnmpUser = true;
                haveShowFipsKey = false;
                showRunningConfig = "show running-config"; // Override SCP or other bad global setting

                // Disable device API settings only:
                apiSnmpServerEnableAllTraps = 0;
            }

            // REAL DEVICE
            else {

                // Cache show version License Type & Level
                licenseType = findLine(version, "License Type:");
                if (licenseType != null) {
                    licenseType = licenseType.substring(14);
                }
                licenseLevel = findLine(version, "License Level:");
                if (licenseLevel != null) {
                    licenseLevel = licenseLevel.substring(15).trim();
                    int b;
                    if ((b = licenseLevel.indexOf("Type:")) > 0) {
                        licenseType = licenseLevel.substring(b+6).trim();
                        licenseLevel = licenseLevel.substring(0,b).trim();
                    }
                }
                if (licenseType != null && licenseType.contains(" ")) {
                    licenseType = "\"" + licenseType + "\"";
                }

                // cached-show inventory (name and serial numbers)
                if (includeCachedShowInventory) {
                    cacheShowInventory(worker);
                }

                // Show current configuration id for debug purposes
                if ("config-id".equals(transIdMethod)) {
                    print_line_exec(worker, "show configuration id");
                }
            }

            //
            // Get iosname
            //
            if (version.contains("Cisco IOS XE Software")
                || version.contains("IOS-XE Software")
                || version.contains("Cisco IOS-XE software")) {
                this.iosname = "ios-xe";
            }

            //
            // Get iosmodel
            //
            Pattern p = Pattern.compile("\n[Cc]isco (\\S+) .*(?:processor |revision )");
            Matcher m = p.matcher(version);
            if (m.find()) {
                this.iosmodel = m.group(1);
            } else {
                p = Pattern.compile("\nLinux Unix \\((\\S+?)\\) processor");
                m = p.matcher(version);
                if (m.find()) {
                    this.iosmodel = "Linux-"+m.group(1);
                }
            }

            //
            // Get iosversion (pick IOS version before XE version)
            //
            p = Pattern.compile("Cisco.*IOS Software.*Version ([0-9]+[A-Za-z0-9\\.():-]+[0-9a-zA-Z)]+)");
            m = p.matcher(version);
            if (m.find()) {
                this.iosversion = m.group(1);
            } else {
                // cat3550 and cat6500 version extraction do not trigger on the above regexp
                p = Pattern.compile("(?:Cisco)?.*IOS.*Software.*Version ([0-9]+[A-Za-z0-9\\.():-]+[0-9a-zA-Z)]+)");
                m = p.matcher(version);
                if (m.find()) {
                    this.iosversion = m.group(1);
                }
            }

            //
            // Get xeversion
            //
            p = Pattern.compile("Version(?:[:])? (03\\S+) ");
            m = p.matcher(version);
            if (m.find()) {
                this.xeversion = m.group(1);
            }

            //
            // Get iosserial
            //
            p = Pattern.compile("Processor board ID (\\S+)");
            m = p.matcher(version);
            if (m.find()) {
                this.iosserial = m.group(1);
            }

            //
            // Enable show system mtu check
            //
            if (this.iosmodel.contains("C296") || this.iosversion.startsWith("12.")) {
                traceVerbose(worker, "Enabling 'show system mtu' check");
                haveShowSystemMtu = true;
            }

        } catch (Exception e) {
            logError(worker, "Failed to setup NED :: ", e);
            throw new NedException("Failed to setup NED :: "+e.getMessage(), e);
        }

        traceInfo(worker, "DONE PROBE "+tickToString(start));
        return new PlatformInfo(iosname, iosversion, iosmodel, iosserial);
    }


    /**
     * Setup auto CDP read inject if enabled
     * @param
     * @throws Exception
     */
    private void setupAutoCdpReadInject(NedWorker worker, String data) throws Exception {

        // Already initialized
        if (cdpDeviceType != 0) {
            return;
        }

        // Detect CDP device type
        data = "\n" + data;
        if (isNetsim()) {
            cdpDeviceType = 1; // shows 'no cdp run' if disabled
        } else if (data.contains("\nno cdp run")) {
            cdpDeviceType = 1; // 90% of the devices, CDP enabled by default
        } else if (data.contains("\ncdp run")) {
            cdpDeviceType = 2; // A few XE devices work the opposite: shows 'cdp run' if enabled
        } else if (isOnlineDevice()) {
            traceInfo(worker, "extracting config - show cdp");
            String showbuf = print_line_simulated(worker, "show cdp");
            if (showbuf.contains("CDP is not enabled")) {
                cdpDeviceType = 2; // Disabled and nothing shows
            } else if (getMatch(showbuf, "(Sending CDPv2 advertisements is[ ]+enabled)") != null) {
                cdpDeviceType = 1; // Enabled and nothing shows
            }
        }

        if (cdpDeviceType == 0) {
            logError(worker, "ERROR: failed to detect CDP device type (default CDP enabled|disabled?)");
            return;
        }
        if (cdpDeviceType == 1) {
            traceInfo(worker, "Detected CDP enabled-by-default device (type 1)");
            return;
        }
        traceInfo(worker, "Detected CDP disabled-by-default device (type 2)");

        // Add static CDP interface injection entry for type 2 devices
        String[] staticEntry = new String[3];
        staticEntry[0] = autoCdpReadInject;
        staticEntry[1] = "no cdp enable";
        staticEntry[2] = "auto-cdp";
        traceInfo(worker, "Adding 'auto cdp-read-inject' ned-setting entry:"
                  +" interface (regex) "+stringQuote(staticEntry[0])
                  +" config "+stringQuote(staticEntry[1]));
        interfaceConfig.add(0, staticEntry);
    }


    /**
     *
     * @param
     * @throws Exception
     */
    private void cacheShowInventory(NedWorker worker) throws Exception {
        setReadTimeout(worker);
        String res = print_line_exec(worker, "show inventory");
        String[] lines = res.split("NAME: ");
        for (int i = 0; i < lines.length; i++) {
            Pattern pattern = Pattern.compile("(\\\".*?\\\"), .*,\\s+SN: (.*)", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(lines[i]);
            if (matcher.find()) {
                String[] entry = new String[2];
                entry[0] = matcher.group(1);
                entry[1] = matcher.group(2).trim();
                traceInfo(worker, "Adding cached-show inventory: NAME="+entry[0]+" SN="+entry[1]);
                cachedShowInventory.add(entry);
            }
        }
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

        this.iosname = platformInfo.name;
        this.iosmodel = platformInfo.model;
        this.iosversion = platformInfo.version;
        this.iosserial = platformInfo.serial;

        setUserSession(worker);
        int th = maapi.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);

        // Get iospolice
        this.iospolice = getIosPolice(worker, th, true);

        // Trace device profile
        try {
            String p = "/ncs:devices/device{"+device_id+"}/device-profile";
            if (maapi.exists(th, p)) {
                this.deviceProfile = ConfValue.getStringByValue(p, maapi.getElem(th, p));
            }
        } catch (MaapiException ignore) {
            // Ignore Exception
        }
        traceInfo(worker, "device-profile = " + this.deviceProfile);

        // Trace NSO features
        rollBackOctal = NsoInfo.capabilities.getProperty("rollback-files-octal", "no");
        traceInfo(worker, "nso-features/rollback-files-octal = "+rollBackOctal);

        // Close transaction
        maapi.finishTrans(th);

        // Create utility classes used by IOS NED
        locks = new NedLocks(this);
        secrets = new NedSecretCliExt(this);
        secrets.setDequoteOutput(true);
        secrets.setDebug(this.devTraceEnable);
        if (isNetsim()) {
            traceInfo(worker, "SECRETS - enabling always-encrypted");
            secrets.setAlwaysCache(true);
        }

        defaults = new NedDefaults(this);
        nedAcl = new NedAcl(this);
        mCrypto = new MaapiCrypto(maapi);

        // ned-settings cisco-ios live-status exec-done-pattern
        String execDonePattern = nedSettings.getString("live-status/exec-done-pattern");
        if (execDonePattern == null) {
            // [cisco-ios] 'issu runversion'
            execDonePattern = "(Initiating active RP failover)|(Target RP will now reload)";
        }

        //
        // NedCommand default auto-prompts:
        //
        String[][] defaultAutoPrompts = new String[][] {
            { execDonePattern, "<exit>" },
            { "([!]{20}|[C]{20}|[.]{20})", "<timeout>" },
            { "\\[OK\\]", null },
            { "\\[Done\\]", null },
            { "timeout is \\d+ seconds:", null },  // ping
            { "Key data:", null }, // crypto key export rsa
            { " has the following attributes:", null }, // crypto pki authenticate
            { ":\\s*$", "<prompt>" },
            { "\\][\\?]?\\s*$", "<prompt>" }
        };
        nedCommand = new NedCommand(this, "ios-stats", "ios", PRIVEXEC_PROMPT, CONFIG_PROMPT,
                                    " Invalid input detected at ", defaultAutoPrompts);

        // Only setup liveStats for connected devices
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
                               "{'show':{'cmd':'show run interface %s | include ip address','arg':['../../name']},"+
                               "'template':'if:interfaces-state_interface_ip:ipv4_address.gili'}");

            nedLiveStats.installParserInfo("if:interfaces-state/if:interface/ip:ipv6/ip:address",
                               "{'show':{'cmd':'show run interface %s | include ipv6 address','arg':['../../name']},"+
                               "'template':'if:interfaces-state_interface_ip:ipv6_address.gili'}");
        }

        traceInfo(worker, "DONE SETUP "+tickToString(start));
    }


    /**
     * Setup NedDiff
     *
     * API syntax:
     *    .add(rule)
     *    .addTemp(rule,true|false)
     *    .add(mode[ ::sub-mode(s)], rule) where '[sub-]mode' is a regex
     *    .addNedSettings(worker,nedSettings)
     *
     * Rule syntax:
     *    line to move :: after|before :: line to stay [:: option(s)]
     *    line to move :: first|last
     *
     * Line match syntax:
     *    =LINE    Line must equal LINE
     *    ==LINE   Line must equal LINE or 'no LINE'
     *    >LINE    Line must start with LINE
     *    >>LINE   Line must start with LINE or 'no LINE'
     *    LINE<    Line must end with LINE
     *    ~LINE    Line must contain LINE
     *    +        Line must not start with 'no', i.e. must be a non-delete
     *    REGEX    (Default) Line must match REGEX. Note: do not forget ^ and $ for exact regex match
     *
     * LINE macros:
     *    <LF>     Replaced with "\n.*?"
     *    <STAY>   Will be expanded to multiple rules per <STAY> stay-entry
     *    <MOVE>   Will be expanded to multiple rules per <MOVE> move-entry
     *
     * LINE options:
     *    ignore:<expr>  Ignore line(s) matching <expr> in this dependency line

     * @param
     */
    private void setupNedDiff(NedWorker worker) {

        nedDiff = new NedDiff(this, this.devTraceEnable);

        // Top-mode rules:
        nedDiff.add(">>router ospf  :: before :: "
                    +"^router bgp \\d+<LF> address-family .+ vrf \\S+<LF>  redistribute ospf ");

        // line vty (for compressed output, cant be turbo-parsed
        nedDiff.add(">no line vty :: after :: >line vty ");

        // crypto
        nedDiff.add(">interface <STAY> :: before :: ^crypto pki trustpoint \\S+<LF> ip-address\\s+<STAY>");
        nedDiff.add(">no crypto map  :: after :: ^interface .+<LF> no(?: ipv6)? crypto map ");
        nedDiff.add(">no crypto gkm  :: after :: >no crypto map ");
        nedDiff.add("crypto map \\S+ \\d+ ipsec-isakmp", "^no set peer \\S+$ :: first");

        // cable
        nedDiff.add("^cable fiber-node \\S+<LF> no service-group profile <STAY>"
                    +" :: before :: >cable profile service-group <STAY>");

        //
        // router bgp * / neighbor *
        //
        final String bgpMode = "router bgp \\d+";
        nedDiff.add(bgpMode, "^neighbor \\S+.*$ :: before :: ^address-family .+<LF>.+ exit-address-family");
        nedDiff.add(bgpMode, "^template peer-session \\S+<LF> no \\S+ :: after :: >no neighbor ");
        nedDiff.add(bgpMode, "=no neighbor <STAY> :: before :: >neighbor <STAY>");
        nedDiff.add(bgpMode, ">no neighbor <STAY> inherit peer-session :: after :: >no neighbor <STAY>");
        nedDiff.add(bgpMode, ">no template  :: after :: >no neighbor");
        nedDiff.add(bgpMode, ">no address-family  :: after :: ^no neighbor \\S+ (remote-as \\d+|peer-group( \\S+)?)$");

        // router bgp * / address-family * / neighbor *
        nedDiff.add("router bgp (\\d+) :: address-family .+",
                    ">no neighbor <STAY> inherit peer-session :: after :: >no neighbor <STAY>");

        // policy-map * / class *
        nedDiff.add("policy-map (\\S+) :: class \\S+", ">no police :: after :: =no priority");
        nedDiff.add("policy-map (\\S+) :: class \\S+", ">no police :: before :: >priority ");
        nedDiff.add("policy-map (\\S+) :: class \\S+", ">police :: after :: >no priority ");

        // policy-map * / class * / police *
        nedDiff.add("policy-map (\\S+) :: class \\S+ :: police .+", ">no conform-action :: before :: >conform-action ");
        nedDiff.add("policy-map (\\S+) :: class \\S+ :: police .+", ">no exceed-action :: before :: >exceed-action ");
        nedDiff.add("policy-map (\\S+) :: class \\S+ :: police .+", ">no violate-action :: before :: >violate-action ");

        //
        // ip access-list standard|extended */
        // ipv6 access-list */
        //
        if (newIpACL) {
            // Make sure delete of access-list rules are before create
            nedDiff.add("ip access-list (standard|extended) \\S+", ">no  :: before :: ^[0-9].+$");
            nedDiff.add("ipv6 access-list \\S+", ">no  :: before :: >sequence ");
        }
        nedDiff.add("^ip access-list (standard|extended) <STAY>"
                    +" :: after :: ^line .+<LF> no access-class <STAY> (in|out).*");
        nedDiff.add("^ip access-list (standard|extended) <STAY>"
                    +" :: after :: ^interface .+<LF> no ip access-group <STAY> (in|out)");
        nedDiff.add(">ipv6 access-list <STAY> :: after :: ^line .+<LF> no ipv6 access-class <STAY> (in|out)");

        //
        // interface * / switchport
        //
        final String[] defaultNo = {
            "ip route-cache",
            "ip proxy-arp",
            "mdix auto",
            "keepalive",
            "logging event link-status",
            "bfd echo",
            "ip redirects",
            "ipv6 redirects"
        };
        final String[] anyLayer = {
            "lldp receive",
            "cdp enable",
            "macro auto processing",
            "port-type"
        };
        StringBuilder ignore = new StringBuilder("ignore");
        for (int d = 0; d < defaultNo.length; d++) {
            ignore.append(":=="+defaultNo[d]);
        }
        for (int d = 0; d < anyLayer.length; d++) {
            ignore.append(":=="+anyLayer[d]);
        }

        //
        // interface * / switchport (mode change to LAYER 2)
        //
        String mode = "interface .+ ,, switchport";
        nedDiff.add(mode, ">switchport :: first :: ignore:=switchport");
        nedDiff.add(mode, ">no  :: before :: >switchport :: "+ignore.toString());
        for (int r = 0; r < defaultNo.length; r++) {
            nedDiff.add(mode, "=" + defaultNo[r] + " :: before :: =switchport");
            nedDiff.add(mode, "=no " + defaultNo[r] + " :: after :: >switchport");
        }

        //
        // interface * / no switchport (mode change to LAYER 3)
        //
        mode = "interface .+ ,, no switchport";
        nedDiff.add(mode, ">no switchport :: last");
        nedDiff.add(mode, "=no switchport :: last");
        nedDiff.add(mode, "+ :: after :: =no switchport :: "+ignore.toString());
        for (int r = 0; r < defaultNo.length; r++) {
            nedDiff.add(mode, "=" + defaultNo[r] + " :: before :: >no switchport");
            nedDiff.add(mode, "=no " + defaultNo[r] + " :: after :: =no switchport");
        }

        //
        // interface * / [no] switchport [LAYER 2 & 3 + reinject]
        //
        String[] spRules = {
            "~ethernet cfm enable :: before :: =switchport mode dot1q-tunnel",
            ">no switchport port-security :: before :: ^no switchport mode (access|trunk|dot1q-tunnel)$" // NSO 4.6
        };
        for (int r = 0; r < spRules.length; r++) {
            nedDiff.add("interface .+ ,, (?:no )switchport", spRules[r]);
        }

        //
        // interface * /
        //
        String[] ifRules = {
            // interface * / ip address
            ">ip address  :: after :: ^(ip )?vrf \\S.*$",
            ">no ip address :: before :: >no encapsulation dot1Q ", // RT25447,RT33983
            ">no ip address  :: before :: ^no (ip )?vrf \\S.*$",

            // interface * / ipv6 address
            ">no ipv6 ospf :: before :: >no ipv6 address", // RT33785

            // interface * / service
            ">no service instance :: before :: >service instance ",

            // interface * / media-type
            "^media-type \\S+$ :: after :: =no ip address",
            "^(media-type sfp|no media-type rj45)$ :: after :: ==mdix auto",
            "=media-type rj45 :: before :: ==mdix auto",

            // interface * / speed
            "=duplex auto :: before :: =speed auto",
            "=duplex full :: before :: >speed 1000",
            "=negotiation auto :: after :: >>speed",

            // interface * / no bfd echo
            "=no bfd echo :: after :: >bfd interval ",
            "=bfd echo :: before :: >no bfd interval",

            // interface * / tunnel mode
            ">no tunnel mpls :: before :: >no tunnel mode mpls",

            // interface * / port-type <-> ethernet dot1a
            "=no ethernet dot1ad nni :: before :: ^no port-type [en]ni",
            "=no ethernet dot1ad nni :: before :: =port-type eni",

            //">>ethernet dot1ad ($1) :: before :: ^no port-type (\\S+)",
            //">no ethernet dot1ad  :: before :: >port-type ",

            // interface * / lldp receive <-> port-type
            "^(lldp receive|cdp enable) :: before :: ^no port-type (nni|eni)",
            "^no (lldp receive|cdp enable) :: after :: ^(port-type (nni|eni))|(ethernet dot1ad nni)",

            // interface * / no cdp enable
            "=no cdp enable :: after :: >no cdp ",

            // interface * / spanning-tree
            ">spanning-tree  :: after :: ^(port-type [en]ni)|(ethernet dot1ad nni)", // if-redeploy 'no spanning-tree'

            // interface * / encapsulation
            ">no rewrite ingress tag pop :: before :: >encapsulation untagged",
            ">no rewrite ingress tag pop :: before :: >encapsulation default"
        };
        for (int r = 0; r < ifRules.length; r++) {
            nedDiff.add("interface .+", ifRules[r]);
        }

        //
        // controller * /
        //
        nedDiff.add("controller .+", ">no rf-chan  :: before :: >rf-chan ");
        nedDiff.add("controller .+", "^rf-chan \\d+<LF> ofdm channel-profile :: last");

        // Add user rules from ned-settings 'write config-dependency' list
        nedDiff.addNedSettings(worker, nedSettings);
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

        /**
         * ShowHandler execute method
         * @param
         * @return
         */
        public String execute(NedWorker worker, String cmd) throws Exception {

            traceInfo(worker, "ShowHandler: "+stringQuote(cmd));

            // '!noop' used for dummy show-entry
            if (cmd.startsWith("!")) {
                return "";
            }

            // ned-setting cisco-ios developer simulate-show *
            String simulated = simulateShow(worker, cmd);
            if (simulated != null) {
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

            // General show command massage
            if (cmd.startsWith("show bgp vpnv4 unicast all neighbors ")) {
                if (cmd.endsWith(" -")) {
                    // Strip any vrf, signified by "-" in the code
                    cmd = cmd.substring(0, cmd.length() - 2);
                } else {
                    Pattern p = Pattern.compile("show bgp vpnv4 unicast all neighbors (\\S+) (\\S+)");
                    Matcher m = p.matcher(cmd);
                    if (m.find()) {
                        cmd = "show bgp vpnv4 unicast vrf "+m.group(2)+" neighbors "+m.group(1);
                    }
                }
            }

            // Send show command and wait for reply
            setReadTimeout(worker);
            session.println(cmd);
            session.expect(Pattern.quote(cmd), worker);
            NedExpectResult res = session.expect(prompts, worker);
            String dump = res.getText();

            // Dirty patch for if:interfaces-state/if:interface/ip:ipv4/ip:address and DHCP
            if (!this.owner.isNetsim() && cmd.startsWith("show run interface ") && dump.contains("ip address dhcp")) {
                dump = showHandlerIfIpv4Address(worker, cmd);
            }

            return dump;
        }

        /**
         * Special ShowHandler for reading 'ip address dhcp' from interface
         * @param
         * @return
         * @throws Exception
         */
        private String showHandlerIfIpv4Address(NedWorker worker, String cmd) throws Exception {
            String ifname = getMatch(cmd, "show run interface (\\S+)");
            String res = print_line_simulated(worker, "show interface "+ifname+" | include Internet address");
            Pattern p = Pattern.compile("Internet address is (\\S+)[/](\\d+)");
            Matcher m = p.matcher(res);
            if (!m.find()) {
                return "";
            }
            long bits = 0xffffffff ^ (1 << 32 - Integer.parseInt(m.group(2))) - 1;
            final String mask = String.format("%d.%d.%d.%d",
                                              (bits & 0x0000000000ff000000L) >> 24,
                                              (bits & 0x0000000000ff0000) >> 16,
                                              (bits & 0x0000000000ff00) >> 8, bits & 0xff);
            return " ip address "+m.group(1)+" "+mask;
        }
    }


    /**
     * Simulate output of show commands in ned-settings
     * @param
     * @return
     */
    protected String simulateShow(NedWorker worker, String line) {

        // Lookup in developer simulate-show ned-settings
        HashMap<String,String> map = new HashMap<>();
        final String path = "developer/simulate-show{\""+line+"\"}/file";
        nedSettings.getMatching(map, path);
        if (map.size() <= 0) {
            return null;
        }

        // Read file
        try {
            final String filename = map.get(path);
            final String output = readFile(filename, false);
            traceInfo(worker, "Simulating '"+line+"' output from '"+filename+"':\n"+output);
            return output;
        } catch (Exception e) {
            return "Failed to simulate "+stringQuote(line)+": "+e.getMessage();
        }
    }


    /**
     * Get data from devices device platform or cached config (deprecated)
     * @param
     * @return Value or "unknown
     * @throws Exception
     */
    protected String getPlatformData(int thr, String leaf) throws Exception {

        // First try devices device platform
        String p = "/ncs:devices/device{"+device_id+"}/platform/" + leaf;
        try {
            if (maapi.exists(thr, p)) {
                return ConfValue.getStringByValue(p, maapi.getElem(thr, p));
            }
        } catch (MaapiException ignore) {
            // Ignore Exception
        }

        // Second try config cached-show version
        if (includeCachedShowVersion) {
            p = confRoot + "cached-show/version/" + leaf;
            try {
                if (maapi.exists(thr, p)) {
                    return ConfValue.getStringByValue(p, maapi.getElem(thr, p));
                }
            } catch (MaapiException ignore) {
                // Ignore Exception
            }
        }

        return UNKNOWN;
    }


    /**
     * Get data police mode setting
     * @param
     * @return Value or default "cirmode"
     */
    private String getIosPolice(NedWorker worker, int thr, boolean cdbLookupOk) {
        String police;

        // (1) Specified in ned-setting cisco-ios api police-format
        if (!"auto".equals(policeFormat)) {
            police = policeFormat.replace(" ", "-");
            traceInfo(worker, "iospolice (ned-setting) = " + police);
            return police;
        }

        // (2) Specified in 'tailfned police'
        if (cdbLookupOk && isNetsim()) {
            String p = confRoot + "tailfned/police";
            try {
                if (maapi.exists(thr, p)) {
                    police = ConfValue.getStringByValue(p, maapi.getElem(thr, p));
                    traceInfo(worker, "iospolice (cdb) = " + police);
                    return police;
                }
            } catch (Exception ignore) {
                // Ignore Exception
            }
        }

        // (3) Auto-detect from iosmodel
        police = null;
        final String cirflat = "cirflat";
        if (iosmodel.contains("ME-3400")) {
            police = "cirmode";
        } else if (iosmodel.contains("C3550") || iosmodel.contains("C3560")) {
            police = "numflat";
        } else if (iosmodel.contains("C3750")) {
            police = cirflat;
        } else if (getMatch(iosmodel, "(C45(?:0[0-9]))") != null) {
            police = "cirmode-bpsflat";
        } else if (iosmodel.contains("WS-C49")) {
            police = "cirmode-bpsflat"; // cat4500
        } else if (iosmodel.contains("ME-4924")) {
            police = "cirmode-bpsflat";
        } else if (getMatch(iosmodel, "(C65(?:0[3469]|13))") != null) {
            police = "cirmode";
        } else if (iosmodel.contains("12404")) {
            police = cirflat;
        } else if (iosmodel.contains("Catalyst")) {
            police = "bpsflat";
        } else if (iosmodel.contains("vios-")) {
            this.iosname = "ViOS";
        } else if (iosmodel.contains("vios_l2")) {
            this.iosname = "ViOS";
            police = cirflat;
        } else if (iosmodel.contains("10000")) {
            police = "numflat";
        }

        if (police != null) {
            traceInfo(worker, "iospolice (auto-detected) = " + police);
        } else {
            police = "cirmode";
            traceInfo(worker, "iospolice (default) = " + police);
        }
        return police;
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
    @Override
    public void show(NedWorker worker, String toptag) throws Exception {

        // Only respond to the first toptag
        if (!"interface".equals(toptag)) {
            worker.showCliResponse("");
            return;
        }

        final long start = tick(0);
        if (session != null && trace) {
            session.setTracer(worker);
        }
        traceInfo(worker, "BEGIN SHOW");

        // Clear cached data
        clearDataCache();

        // Get config from device and perform input transformation
        String res = modifyInput(worker, getConfig(worker));
        final String transformedConfig = res;

        // Append config which cant be included in transaction-id
        res = getConfigOnly(worker, res);

        // Trace config
        traceVerbose(worker, "SHOW-BUF=\n+++ show begin\n"+res+"\n+++ show end");

        // ned-settings cisco-ios extended-parser
        try {
            // Reset locks
            locks.reset(worker);

            // Turbo-parse config
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
            logError(worker, "extended-parser "+nedSettings.getString(EXTENDED_PARSER)+" exception ERROR: ", e);
            if (!nedSettings.getBoolean("developer/extended-parser-fallback")) {
                throw e;
            }
            this.turboParserEnable = false;
            this.robustParserMode = false;
        } finally {
            this.syncFile = null;
        }

        // New NSO feature, set provisional transaction-id in show()
        if (transIdMethod.startsWith("config-hash")) {
            this.lastTransactionId = calculateTransId(worker, transformedConfig);
            traceInfo(worker, "SHOW TRANS_ID = "+this.lastTransactionId);
            if (nedSettings.getBoolean("read/transaction-id-provisional")) {
                NedCommonLib.setProvisionalTransId(worker, this.lastTransactionId);
            }
        }

        traceInfo(worker, "DONE SHOW "+tickToString(start));
        worker.showCliResponse(res);
    }


    /**
     * Get running-config from device
     * @param
     * @return
     * @throws Exception
     */
    private String getConfig(NedWorker worker) throws Exception {

        NedProgress.Progress progress = reportProgressStart(this, NedProgress.READ_CONFIG);
        try {
            final long start = setReadTimeout(worker);
            String res;

            //
            // (1) showOffline
            //
            if (this.offlineData != null) {
                traceInfo(worker, "Reading offline config...");
                res = this.offlineData;
            }

            //
            // (2) devices device <dev> live-status exec any sync-from-file <file|trace>
            //
            else if (this.syncFile != null) {
                traceInfo(worker, "Reading sync-from-file config...");
                res = readFile(this.syncFile, true);
                res = res.replace("\r\r\r\n", "\r\n");
                res = res.replace("\r\r\n", "\r\n");
            }

            //
            // (3) ned-settings cisco-ios read show-running-method scp-transfer
            //
            else if (showRunningConfig.startsWith("scp-transfer")) {
                traceInfo(worker, "Reading SCP config...");
                if (nedSettings.getString("proxy/remote-connection") != null) {
                    throw new NedException("ned-settings: read/show-running-method scp-transfer is not supported with proxy mode");
                }
                try {
                    res = scpGetConfig(worker);
                } catch (Exception e) {
                    if (!"scp-transfer-fallback".equals(showRunningConfig)) {
                        throw e;
                    }
                    traceInfo(worker, "WARNING: SCP transfer failed '"+e.getMessage()
                              +"' - fallback to show-running-method");
                    res = showRunningConfig(worker);
                }
            }

            //
            // (4) <command>
            //
            else {
                res = showRunningConfig(worker);
            }

            //
            // Trim running-config
            //
            res = trimInput(worker, res);

            //
            // Get config from additional show commands
            //
            res = getExtraConfig(worker, res);

            // Done
            traceInfo(worker, "Reading config done "+tickToString(start));
            reportProgressStop(progress);
            return res;

        } catch (Exception e) {
            reportProgressStop(progress, NedProgress.ERROR);
            throw e;
        }
    }


    /**
     * Run show-running-config command(s)
     * @param
     * @return
     * @throws Exception
     */
    private String showRunningConfig(NedWorker worker) throws Exception {

        traceInfo(worker, "Reading running config...");

        // Run show command(s)
        String[] cmds = showRunningConfig.split(" ; ");
        StringBuilder sb = new StringBuilder();
        for (int n = 0; n < cmds.length; n++) {
            String res = print_line_exec(worker, cmds[n]);
            if (isExecError(res)) {
                throw new NedException("failed to show config using '"+cmds[n]+"'");
            }
            if (cmds.length > 1) {
                res = trimInput(worker, res);
            }
            sb.append(res);
        }
        //traceDev(worker, "SHOW-RUN-BUF=\n+++ begin\n"+sb.toString()+"\n+++ end");
        return sb.toString();
    }


    /**
     * Append config from additional show and CDB which should not be included in transaction-id
     * @param
     * @return
     * @throws Exception
     */
    private String getConfigOnly(NedWorker worker, String res) throws Exception {

        traceVerbose(worker, "Reading config-only...");
        final long start = tick(0);

        StringBuilder first = new StringBuilder();
        StringBuilder last = new StringBuilder();

        // Start transaction if none open
        setUserSession(worker);
        int th = maapi.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);
        try {

            //
            // (1) tailfned api <feature>
            //
            if (newIpACL) {
                traceInfo(worker, "transformed <= inserted tailfned api new-ip-access-list");
                first.append("tailfned api new-ip-access-list\n");
            }
            if (newAAAList) {
                traceInfo(worker, "transformed <= inserted tailfned api new-aaa-list-syntax");
                first.append("tailfned api new-aaa-list-syntax\n");
            }
            if (resequenceACL) {
                traceInfo(worker, "transformed <= inserted tailfned api resequence-access-list");
                first.append("tailfned api resequence-access-list\n");
            }
            if (newSnmpServerHost) {
                traceInfo(worker, "transformed <= inserted tailfned api new-snmp-server-host");
                first.append("tailfned api new-snmp-server-host\n");
            }
            if (expandedLineVtyFormat) {
                traceInfo(worker, "transformed <= inserted tailfned api expanded-line-vty-format");
                first.append("tailfned api expanded-line-vty-format\n");
            }
            if (apiNewMlsQos) {
                traceInfo(worker, "transformed <= inserted tailfned api new-mls-qos");
                first.append("tailfned api new-mls-qos\n");
            }
            if (apiSnmpServerEnableAllTraps != 0) {
                traceInfo(worker, "transformed <= inserted tailfned api snmp-server-enable-all-traps");
                first.append("tailfned api snmp-server-enable-all-traps\n");
            }

            //
            // (2) tailfned police
            //
            String match;
            final String police = getIosPolice(worker, th, true);
            traceInfo(worker, "transformed <= inserted tailfned police "+police);
            if ((match = getMatch(res, "(tailfned police .*)")) != null) {
                res = res.replace(match, "tailfned police "+police);
            } else {
                first.append("tailfned police "+police+"\n");
            }

            //
            // (3) Online devices only:
            //
            if (isOnlineDevice()) {

                // DEFAULTS - inject hidden default values (may call show queueing)
                res = defaults.inject(worker, res, th);

                // Insert 'show system mtu' config
                getConfigSystemMtu(worker, last);

                // Insert 'snmp-server ... v3 ...' config from show snmp user
                // WARNING: Can't inject from getTransId() with commit queues.
                getSnmpUser(worker, res, th, last);
            }

            //
            // (4) Device CDB insertions:
            //
            if (isDevice()) {
                // Inject from CDB:
                //  key config-key password-encrypt
                //  cts credentials id * password
                injectCachedExec(worker, first, th);

                // IOS changed the hidden default of 'service pad' in 17.3
                if (iosversion.contains("17.3") && !res.contains("\nservice pad")) {
                    traceInfo(worker, "transformed <= inserted 'no service pad'");
                    first.append("no service pad\n");
                }
            }

            //
            // (5) Insert cached-show 'config'
            //
            last.append("\n");
            if (includeCachedShowVersion) {
                last.append("cached-show version version " + iosversion + "\n");
                if (!xeversion.isEmpty()) {
                    last.append("cached-show version xe-version " + xeversion + "\n");
                }
                last.append("cached-show version model " + iosmodel + "\n");
                if (licenseLevel != null) {
                    last.append("cached-show version license level " + licenseLevel + "\n");
                }
                if (licenseType != null) {
                    last.append("cached-show version license type " + licenseType + "\n");
                }
            }
            if (includeCachedShowInventory) {
                for (int i = 0; i < cachedShowInventory.size(); i++) {
                    String[] entry = cachedShowInventory.get(i);
                    last.append("cached-show inventory name " + entry[0]);
                    if (!entry[1].trim().isEmpty()) {
                        last.append(" sn " + entry[1]);
                    }
                    last.append("\n");
                }
            }

        } finally {
            maapi.finishTrans(th);
        }

        // Done
        traceVerbose(worker, "Reading config-only done "+tickToString(start));
        return first.toString() + res + last.toString();
    }


    /**
     * Extract config from additional show commands
     * @param
     * @return
     * @throws Exception
     */
    private String getExtraConfig(NedWorker worker, String res) throws Exception {

        if (!isOnlineDevice()) {
            return res;
        }

        traceVerbose(worker, "Extracting config...");
        StringBuilder first = new StringBuilder();
        StringBuilder last = new StringBuilder();

        // Extend read-timeout
        lastTimeout = setReadTimeout(worker);
        final long start = lastTimeout;

        // Insert missing 'show vtp password' config
        getVtpPassword(worker, last);

        // Insert missing 'show boot' config
        getConfigBoot(worker, last);

        // Insert config shown in "show version"
        getConfigVersion(worker, last);

        // Insert 'logging console X' shown in "show logging"
        getConfigLogging(worker, first);

        // Insert missing VLAN config from show vlan
        getConfigVlan(worker, res, last);

        // Insert missing fips key
        getConfigFipsKey(worker, last);

        // Setup CDP auto read inject of 'no cdp run' and 'interface * / no cdp enable'
        if (autoCdpReadInject != null) {
            setupAutoCdpReadInject(worker, res);
            if (cdpDeviceType == 2 && !res.contains("\ncdp run")) {
                traceInfo(worker, "transformed <= inserted: \"no cdp run\" first in config [auto CDP]");
                first.append("no cdp run\n");
            }
        }

        // Append output from 'show line' if needed for config line modifications
        Pattern p = Pattern.compile(LINE_RANGE3_REGEX, Pattern.DOTALL);
        Matcher m = p.matcher(res);
        if (m.find()) {
            traceInfo(worker, "extracting config - show line");
            String linebuf = print_line_simulated(worker, "show line");
            if (!isExecError(linebuf)) {
                last.append("\nSHOW-LINE:\n"+linebuf);
            }
        }

        traceVerbose(worker, "Extracting config done "+tickToString(start));
        if (first.length() > 0) {
            traceVerbose(worker, "extra-config-first:\n"+first.toString());
        }
        traceVerbose(worker, "extra-config-last:\n"+last.toString());

        return first.toString() + res + last.toString();
    }

    /**
     * Show vtp password
     * @param
     * @throws Exception
     */
    private void getVtpPassword(NedWorker worker, StringBuilder sb) throws Exception {
        traceInfo(worker, "extracting config - show vtp password");
        String vtpPassword = print_line_exec(worker, "show vtp password");
        if ((vtpPassword = findLine(vtpPassword, "VTP Password:")) != null) {
            vtpPassword = vtpPassword.substring(13).trim();
            if (!vtpPassword.isEmpty()) {
                vtpPassword = "vtp password " + vtpPassword;
                sb.append(vtpPassword.trim() + "\n");
                return;
            }
        }
    }

    /**
     * Show boot config
     * @param
     * @throws Exception
     */
    private void getConfigBoot(NedWorker worker, StringBuilder sb) throws Exception {
        if (!haveShowBoot) {
            return;
        }

        traceInfo(worker, "extracting config - show boot");
        String boot = print_line_exec(worker, "show boot");
        if ((boot = findLine(boot, "BOOT path-list:")) != null) {
            boot = boot.substring(15).trim();
            if (!boot.isEmpty()) {
                boot = "boot system " + boot;
                sb.append(boot.trim() + "\n");
                return;
            }
        }

        traceInfo(worker, "Disabling 'show boot' check");
        haveShowBoot = false;
    }


    /**
     * Show system mtu config
     * @param
     * @throws Exception
     */
    private void getConfigSystemMtu(NedWorker worker, StringBuilder sb) throws Exception {
        if (!haveShowSystemMtu) {
            return;
        }

        traceInfo(worker, "extracting config - show system mtu");
        String mtu = print_line_exec(worker, "show system mtu");
        if (!isExecError(mtu)) {
            String match;
            if ((match = getMatch(mtu, "System MTU size is (\\d+) bytes")) != null && !"1500".equals(match)) {
                sb.append("system mtu "+match+"\n");
            }
            if ((match = getMatch(mtu, "System Jumbo MTU size is (\\d+) bytes")) != null && !"1500".equals(match)) {
                sb.append("system mtu jumbo "+match+"\n");
            }
            if ((match = getMatch(mtu, "Routing MTU size is (\\d+) bytes")) != null && !"1500".equals(match)) {
                sb.append("system mtu routing "+match+"\n");
            }
            return;
        }

        traceInfo(worker, "Disabling 'show system mtu' check");
        haveShowSystemMtu = false;
    }


    /**
     * Show fips authorization-key
     * @param
     * @throws Exception
     */
    private void getConfigFipsKey(NedWorker worker, StringBuilder sb) throws Exception {
        if (!haveShowFipsKey) {
            return;
        }

        traceInfo(worker, "extracting config - show fips authorization-key");
        String key = print_line_exec(worker, "show fips authorization-key");
        if (!isExecError(key)) {
            String match;
            if ((match = getMatch(key, "FIPS: Stored key \\S+[ ]+:[ ]+(\\S+)")) != null) {
                sb.append("fips authorization-key "+match+"\n");
            }
            return;
        }

        traceInfo(worker, "Disabling 'show fips authorization-key' check");
        haveShowFipsKey = false;
    }


    /**
     * Extract config from show logging xml command
     * @param
     * @throws Exception
     */
    private void getConfigLogging(NedWorker worker, StringBuilder sb) throws Exception {

        traceInfo(worker, "extracting config - show logging xml");

        // NOTE: Not supported on older IOS versions [12.2(33)]
        String showbuf = print_line_exec(worker, "show logging xml");
        if (isExecError(showbuf)) {
            traceInfo(worker, "WARNING: unable to determine logging status due to too old IOS");
            return;
        }

        getConfigLoggingType(showbuf, "console", sb);
        getConfigLoggingType(showbuf, "monitor", sb);
        getConfigLoggingType(showbuf, "buffer", sb);
        traceInfo(worker, "transformed <= inserted logging config from 'show logging xml'");
    }


    /**
     * Utility method for getConfigLogging
     * @param
     * @return
     */
    private void getConfigLoggingType(String showbuf, String type, StringBuilder sb) {
        String name = type + "-logging";
        String line = findLine(showbuf, "<"+name);
        if (line == null) {
            return;
        }
        if (line.trim().startsWith("<"+name+">disabled<")) {
            return;
        }
        Pattern p = Pattern.compile("<"+name+" level=\"(\\S+?)\" ");
        Matcher m = p.matcher(line);
        if (!m.find()) {
            return;
        }

        if ("buffer".equals(type)) {
            type = "buffered";
        }
        sb.append("logging " + type + " " + m.group(1) + "\n");
    }


    /**
     * Extract config from show version command
     * @param
     * @throws Exception
     */
    private void getConfigVersion(NedWorker worker, StringBuilder sb) throws Exception {

        traceInfo(worker, "extracting config - show version | i password-recovery");

        String showbuf = print_line_exec(worker, "show version | include password-recovery");
        if (!showbuf.contains("password-recovery")) {
            return;
        }
        if (showbuf.contains("enabled")) {
            traceInfo(worker, "transformed <= inserted 'service password-recovery' from 'show version'");
            sb.append("service password-recovery\n");
        } else if (showbuf.contains("disabled")) {
            traceInfo(worker, "transformed <= inserted 'no service password-recovery' from 'show version'");
            sb.append("no service password-recovery\n");
        }
    }


    /**
     * Extract vlan config from show vlan
     * @param
     * @throws Exception
     */
    private void getConfigVlan(NedWorker worker, String dump, StringBuilder sb) throws Exception {
        String res;
        int i;
        boolean vtpClient = false;
        final int sblength = sb.length();
        if (haveShowVtpStatus) {

            traceInfo(worker, "extracting config - show vtp status");
            String vtpStatus = print_line_simulated(worker, "show vtp status");
            if (isExecError(vtpStatus)) {
                traceInfo(worker, "Disabling 'show vtp status' check");
                haveShowVtpStatus = false;
            }

            // Extract VTP "config" from 'show vtp status'
            else {
                // vtp mode
                if ((res = findLine(vtpStatus, "VTP Operating Mode")) != null) {
                    String mode = getMatch(res, "VTP Operating Mode\\s+:\\s+(?:Primary )?(\\S+)");
                    if (mode != null) {
                        mode = mode.trim().toLowerCase();
                        sb.append("vtp mode " + mode + "\n");
                        if ("client".equals(mode)) {
                            vtpClient = true;
                        }
                    }
                }

                // vtp domain
                if ((res = findLine(vtpStatus, "VTP Domain Name")) != null
                    && (i = res.indexOf(':')) > 0) {
                    String value = res.substring(i+1).trim();
                    if (!value.isEmpty()) {
                        sb.append("vtp domain " + value + "\n");
                    }
                }

                // vtp version
                if ((res = getMatch(vtpStatus, "VTP Version[ ]+[:] running VTP(\\d+)")) != null) {
                    sb.append("vtp version " + res + "\n");
                } else if (((res = findLine(vtpStatus, "VTP version running")) != null // 2960 & 7600
                            || (res = findLine(vtpStatus, "VTP Version")) != null) // 4500
                           && (i = res.indexOf(':')) > 0) {
                    String value = res.substring(i+1).trim();
                    if (!value.isEmpty()) {
                        sb.append("vtp version " + value + "\n");
                    }
                }

                // vtp pruning
                if ((res = findLine(vtpStatus, "VTP Pruning Mode")) != null) {
                    String value = res.replaceAll("VTP Pruning Mode\\s+:\\s+(\\S+)", "$1").trim();
                    if ("Enabled".equals(value)) {
                        sb.append("vtp pruning\n");
                    }
                }

                traceInfo(worker, "transformed <= inserted VTP config from 'show vtp status'");
            }
        }

        // If VTP Client, do not add vlan's to config.
        if (vtpClient) {
            traceInfo(worker, "Found VTP Client, do not list vlan(s) using show vlan");
            return;
        }

        //
        // Add vlan entries:
        //

        // First try 'show vlan'
        res = "";
        if (haveShowVlan) {
            traceInfo(worker, "extracting config - show vlan");
            res = print_line_simulated(worker, "show vlan");
            if (res.indexOf("\n----") < 0) {
                traceInfo(worker, "Disabling 'show vlan' check");
                haveShowVlan = false;
            }
        }

        // If that fails, then try 'show vlan-switch'
        if (haveShowVlanSwitch && !haveShowVlan) {
            traceInfo(worker, "extracting config - show vlan-switch");
            res = print_line_simulated(worker, "show vlan-switch");
            if (isExecError(res)) {
                traceInfo(worker, "Disabling 'show vlan-switch' check");
                haveShowVlanSwitch = false;
                return;
            }
        }

        // No support for either show vlan or show vlan-switch
        if (res.isEmpty() || (!haveShowVlanSwitch && !haveShowVlan)) {
            return;
        }

        // Strip all text before first entry
        if ((i = res.indexOf("\n----")) < 0) {
            return;
        }
        if ((i = res.indexOf('\n', i+1)) < 0) {
            return;
        }
        res = res.substring(i+1);

        // Parse lines, create:
        // vlan #
        //  name <name>
        // !
        String[] vlans = res.split("\r\n");
        for (i = 0; i < vlans.length; i++) {
            if (vlans[i] == null || "".equals(vlans[i])) {
                break;
            }
            // Skip multi line entries. Each new starts with a digit
            if (!Character.isDigit(vlans[i].trim().charAt(0))) {
                continue;
            }
            String[] tokens = vlans[i].split(" +");
            if (tokens.length < 3) {
                break;
            }
            int status = vlans[i].indexOf(" active ");
            if (status < 0) {
                continue;
            }
            String vlan = "vlan " + tokens[0];
            if (dump.contains("\n"+vlan+"\r") || dump.contains("\n"+vlan+" \r")) {
                continue;
            }
            sb.append(vlan + "\n");

            // vlan * / name
            String name = vlans[i].substring(tokens[0].length(), status).trim();
            if (!name.isEmpty()
                && !(name.startsWith("VLAN") && name.endsWith(tokens[0]))) { // ignore default names
                sb.append(" name "+name+"\n");
            }

            sb.append("!\n");
        }

        if (sb.length() > sblength) {
            traceInfo(worker, "transformed <= inserted vlan config from 'show vlan[-switch]'");
        }
    }


    /**
     * Get SNMP user config using data from both 'show snmp user' and CDB
     * @param
     * @throws Exception
     */
    private void getSnmpUser(NedWorker worker, String dump, int th, StringBuilder sb) throws Exception {

        if (!haveShowSnmpUser) {
            return;
        }

        //
        // Get snmp user info
        //
        traceInfo(worker, "extracting config - show snmp user");
        String res = print_line_exec(worker, "show snmp user");
        if (isExecError(res)) {
            traceInfo(worker, "Disabling 'show snmp user' check");
            haveShowSnmpUser = false;
            return;
        }
        if (res.contains("SNMP agent not enabled")) {
            return;
        }

        //
        // Parse output and inject passwords from CDB
        //
        try {
            int b = res.indexOf("\nUser name: ");
            if (b < 0) {
                return;
            }

            while (b >= 0) {

                // User name:
                final String name = getString(res, b+12);
                String root = confRoot + "snmp-server/user{"+name+"}/";

                // Engine ID:
                int e = res.indexOf("\nEngine ID: ", b);
                if (e < 0) {
                    break;
                }
                final String engineID = getString(res, e+12).toLowerCase().trim();
                traceInfo(worker, "snmp-server user: name="+name+" engineID="+engineID);

                // Lookup remote
                String remote = "";
                ConfValue val;
                Pattern p = Pattern.compile("snmp-server engineID remote (\\S+)(?: udp-port (\\S+))? "+engineID);
                Matcher m = p.matcher(dump);
                if (m.find()) {
                    remote = " remote "+m.group(1);
                    root = confRoot + "snmp-server/user-remote/user{"+name+" "+m.group(1)+"}/";
                    traceVerbose(worker, "SNMP-USER remote="+m.group(1)+" m.group(2)="+m.group(2));
                    if (m.group(2) != null) {
                        remote += (" udp-port "+m.group(2));
                    } else if ((val = maapi.safeGetElem(th, new ConfPath(root+"udp-port"))) != null) {
                        remote += (" udp-port "+val.toString());
                    }
                }

                // Authentication Protocol:
                e = res.indexOf("\nAuthentication Protocol: ", b);
                if (e < 0) {
                    break;
                }
                final String auth = getString(res, e+26).toLowerCase();

                // Privacy Protocol:
                e = res.indexOf("\nPrivacy Protocol: ", b);
                if (e < 0) {
                    break;
                }
                String priv = getString(res, e+19).toLowerCase().trim();
                if (priv.indexOf("aes") == 0) {
                    priv = "aes " + priv.substring(3);
                }

                // Group-name:
                int end = res.indexOf("\nGroup-name: ", b);
                if (end < 0) {
                    break;
                }
                String group = getString(res, end+13);

                // Get access list info
                String acl = "";
                e = res.indexOf("IPv6 access-list: ", b);
                if (e > 0 && e < end) {
                    acl = "ipv6 " + getString(res, e+18);
                } else {
                    e = res.indexOf("access-list: ", b);
                    if (e > 0 && e < end) {
                        acl = getString(res, e+13);
                    }
                }

                // Lookup 'read snmp-server-user-defaults *' ned-setting entry
                List<Map<String,String>> pwdList = nedSettings.getListEntries("read/snmp-server-user-defaults");
                Map<String,String> pwdE = null;
                for (int i = 0; i < pwdList.size(); i++) {
                    Map<String,String> entry = pwdList.get(i);
                    final String regexp = entry.get("regexp");
                    if (regexp == null) {
                        pwdE = entry;
                        break;
                    }
                    Pattern p2 = Pattern.compile(regexp);
                    Matcher m2 = p2.matcher(name);
                    if (m2.find()) {
                        pwdE = entry;
                        break;
                    }
                }

                // Begin making entry
                sb.append("snmp-server user "+name+" "+group+remote+" v3");

                // Add optional 'auth' params
                String password;
                if (!"none".equals(auth)) {
                    String authPw = "NOT-SET-IN-NCS";
                    if ((val = maapi.safeGetElem(th, new ConfPath(root+"auth-password"))) != null) {
                        authPw = val.toString();
                    } else if (pwdE != null && (password = pwdE.get("auth-password")) != null) {
                        final String id = pwdE.get(NSKEY); // "id
                        traceInfo(worker, "   using read/snmp-server-user-defaults "+id+" auth-password");
                        authPw = maapiDecrypt(password);
                    }
                    sb.append(" auth " + auth + " " +authPw);
                }

                // Add optional 'priv' params
                if (!"none".equals(priv)) {
                    String privPw = "NOT-SET-IN-NCS";
                    if ((val = maapi.safeGetElem(th, new ConfPath(root+"priv-password"))) != null) {
                        privPw = val.toString();
                    } else if (pwdE != null && (password = pwdE.get("priv-password")) != null) {
                        final String id = pwdE.get(NSKEY); // "id
                        traceInfo(worker, "   using read/snmp-server-user-defaults "+id+" priv-password");
                        privPw = maapiDecrypt(password);
                    }
                    sb.append(" priv " + priv + " " + privPw);
                }

                // Add optional 'access' params
                if (!acl.trim().isEmpty()) {
                    sb.append(" access " + acl.trim());
                }

                // End of entry
                sb.append("\n");

                // Get next entry
                b = res.indexOf("\nUser name: ", b+12);
            }
        } catch (Exception ex) {
            throw new NedException("getSnmpUser():", ex);
        }

        traceInfo(worker, "transformed <= inserted SNMP config from 'show snmp user'");
    }


    /**
     * Get config using SCP
     * @param
     * @return
     * @throws Exception
     */
    private String scpGetConfig(NedWorker worker) throws Exception {

        final int retryCount = nedSettings.getInt("connection/number-of-retries");
        final int waitTime = nedSettings.getInt("connection/time-between-retry");

        // Connect using SSH
        Connection scpConn = new Connection(ip.getHostAddress(), port);
        for (int retries = retryCount; retries >= 0; retries--) {
            traceInfo(worker, "SCP connecting to " + ip.getHostAddress()
                      +":"+port+" ["+(1+retryCount-retries)+"/"+retryCount+"]");
            try {
                scpConn.connect(null, 0, connectTimeout);
                break;
            } catch (Exception e) {
                if (retries == 0) {
                    throw new NedException("read/show-running-method scp-transfer failed to open SCP connection", e);
                } else {
                    resetTimeout(worker, this.connectTimeout + (waitTime * 1000), 0);
                    sleep(worker, waitTime * (long)1000, true);
                }
            }
        }

        // Authenticate SSH connection
        scpConn.authenticateWithPassword(ruser, pass);
        if (!scpConn.isAuthenticationComplete()) {
            throw new NedException("read/show-running-method scp-transfer isAuthenticationComplete() = false");
        }
        traceInfo(worker, "SCP authenticated");

        // Send SCP get command
        final String file = "running-config";
        traceInfo(worker, "SCP fetching file: " + file);
        ch.ethz.ssh2.Session scpSession = scpConn.openSession();
        scpSession.execCommand("scp -f " + file);

        // Get running-config file
        BufferedReader reader = null;
        StringBuilder sb = new StringBuilder();
        try {
            SCPClient scpClient = new SCPClient(scpConn);
            InputStream in = new SCPInputStream(scpClient, scpSession);
            reader = new BufferedReader(new InputStreamReader(in));
            String line;
            lastTimeout = setReadTimeout(worker);
            int n = 0;
            while ((line = reader.readLine()) != null) {
                sb.append(line+"\r\n");
                // Update timeout each TIMEOUT_MOD
                if ((++n % TIMEOUT_MOD) == 0) {
                    lastTimeout = resetReadTimeout(worker, lastTimeout);
                }
            }
        } catch (Exception e) {
            throw new NedException("SCP download Exception: "+e.getMessage(), e);
        } finally {
            if (reader != null) {
                reader.close();
            }
            if (scpSession != null) {
                scpSession.close();
            }
            scpConn.close();
        }

        String res = sb.toString();
        traceInfo(worker, "SCP got "+res.length()+" bytes");

        // Replace single char '^C' with two char ^C
        byte[] bytes = res.getBytes();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < res.length(); ++i) {
            if (bytes[i] == 3) {
                result.append("^");
                result.append("C");
            } else {
                result.append(res.charAt(i));
            }
        }
        res = result.toString();
        traceVerbose(worker, "\nSHOW_SCP:\n'"+res+"'");
        return res;
    }


    /**
     * Trim input config
     * @param
     * @return
     */
    private String trimInput(NedWorker worker, String res) {
        int d;

        // Strip everything before and including the following comments:
        int i = res.indexOf("Current configuration");
        if (i >= 0 && (d = res.indexOf('\n', i)) > 0) {
            res = res.substring(d+1);
        }

        i = res.indexOf("Last configuration change");
        if (i >= 0 && (d = res.indexOf('\n', i)) > 0) {
            res = res.substring(d+1);
        }

        i = res.indexOf("No configuration change since last restart");
        if (i >= 0 && (d = res.indexOf('\n', i)) > 0) {
            res = res.substring(d+1);
        }

        i = res.indexOf("No entries found.");
        if (i >= 0 && (d = res.indexOf('\n', i)) > 0) {
            res = res.substring(d+1);
        }

        i = res.lastIndexOf("NVRAM config last updated"); // multiple entries
        if (i >= 0 && (d = res.indexOf('\n', i)) > 0) {
            res = res.substring(d+1);
        }

        // Strip all text after and including the last 'end'
        i = res.lastIndexOf("\nend");
        if (i >= 0) {
            res = res.substring(0,i);
        }

        // Strip config of dynamic info
        StringBuilder sb = new StringBuilder();
        String[] lines = res.trim().split("\n");
        for (int n = 0; n < lines.length; n++) {
            String trimmed = lines[n].trim();
            boolean strip = false;

            // clock-period, device may change it, i.e. not config
            if (trimmed.startsWith("ntp clock-period")) {
                strip = true;
            }

            // Strip console log messages
            else if (trimmed.startsWith("%")) {
                strip = true;
            }

            // Strip incomplete comments (e.g. crypto ikev2 profile)
            else if (trimmed.startsWith("! Profile incomplete")) {
                strip = true;
            } else if (trimmed.startsWith("! This profile is incomplete")) {
                strip = true;
            }

            // SET_TIMEOUT
            else if (trimmed.endsWith(" SET_TIMEOUT")) {
                strip = true;
            }

            // Strip and trace or add
            if (strip) {
                traceDev(worker, "transformed <= stripped '"+lines[n]+"'");
            } else {
                sb.append(lines[n]+"\n");
            }
        }
        res = sb.toString();

        // Patch IOS bug showing "switch virtual domain */ dual-active" line outside scope
        res = res.replace("\n!\r\n dual-active ", "\n dual-active ");

        // After reading/stripping device config, trim for consistency
        res = res.trim() + "\r\n";

        return res;
    }


    /**
     * NETSIM line-by-line input transformations
     * @param
     * @return
     */
    private String modifyInputNetsim(NedWorker worker, String res) {
        String toptag = "";
        StringBuilder sb = new StringBuilder();
        String[] lines = res.split("\n");
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String ninput = null;

            // Update toptag
            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = trimmed;
            }

            //
            // ' description '
            //
            if (line.contains(" description ")) {
                ninput = quoteDescription(toptag, line);
            }

            //
            // voice translation-rule * / rule
            //
            else if ("no".equals(rollBackOctal)
                     && toptag.startsWith("voice translation-rule ") && trimmed.startsWith("rule ")) {
                ninput = line.replace("\\", "\\\\");
            }

            //
            // line * / exec-timeout 10 0
            //
            else if (toptag.startsWith("line ") && line.startsWith("line ")) {
                traceVerbose(worker, "transformed <= injected: 'exec-timeout 10 0' first in "+trimmed);
                sb.append(line+"\n exec-timeout 10 0\n");
                continue;
            }

            //
            // Append (modified) line to buffer
            //
            if (ninput != null && !ninput.equals(lines[n])) {
                if (ninput.isEmpty()) {
                    traceVerbose(worker, "transformed <= stripped '"+trimmed+"'");
                    continue;
                }
                traceVerbose(worker, "transformed <= '"+trimmed+"' to '"+ninput.trim()+"'");
                sb.append(ninput+"\n");
            } else if (lines[n] != null && !lines[n].isEmpty()) {
                sb.append(line+"\n");
            }
        }

        return sb.toString();
    }


    /**
     * Modify input from device
     * @param
     * @return
     * @throws Exception
     */
    private String modifyInput(NedWorker worker, String res) throws Exception {
        final long start0 = tick(0);
        int i;
        String match;
        String[] group;

        traceInfo(worker, "Transforming input config...");
        NedProgress.Progress progress = reportProgressStart(this, NedProgress.TRANSFORM_IN);

        //
        // Extract line config from dump
        String lineDump = "unknown";
        i = res.indexOf("\nSHOW-LINE:\n");
        if (i > 0) {
            traceVerbose(worker, "extracted 'show line' data from config dump");
            lineDump = res.substring(i + 11).trim();
            res = res.substring(0, i + 1);
        }

        //
        // Inject config
        //
        res = injectInput(worker, "\n" + res);

        //
        // NETSIM - transform and leave early
        //
        if (isNetsim() && syncFile == null && offlineData == null) {
            res = modifyInputNetsim(worker, res);
            traceInfo(worker, "Transforming NETSIM input config done "+tickToString(start0));
            reportProgressStop(progress);
            return res;
        }

        //
        // REAL DEVICES BELOW
        //

        //
        // Quote multi-line texts
        //   group(1) = command
        //   group(3) = text to quote
        //   group(4) = additional unquoted append (optional)
        //
        traceVerbose(worker, "quoting multi-line texts");
        String[] quoteTexts = {
            // menu <name> title ^C
            // <title text>
            // ^C
            "\n(menu \\S+ (?:title|prompt)) (\\^C)(.*?)\\^C",

            // aaa authentication banner|fail-message
            "\n(aaa authentication banner) (\\^C)(.*?)\\^C",
            "\n(aaa authentication fail-message) (\\^C)(.*?)\\^C",

            //   macro name <name>
            //    xxx
            //    yyy
            //   @
            "\n(macro name \\S+)(\r\n)(.*?\r\n)@",

            // banner <name>
            "\n(banner \\S+) (\\S\\S)(.*?)\\2\\S*?\r",

            // line * / vacant-message <name>
            // line * / refuse-message <name>
            "\n( (?:vacant|refuse)-message) (\\S\\S)(.*?)\\2\\S*?\r",

            // certificate <name>
            //  aaa bbb ccc
            //  ... ... ...
            //  xxx yyy zzz
            // \tquit
            "\n( certificate .*?(\r\n))(.*?\r\n)[ \t]+(quit)"
        };
        for (int n = 0; n < quoteTexts.length; n++) {
            Pattern p = Pattern.compile(quoteTexts[n], Pattern.DOTALL);
            Matcher m = p.matcher(res);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String line = m.group(1);
                String quoted = stringQuote(m.group(3));
                if (m.groupCount() == 4) {
                    quoted += ("\r\n" + m.group(4));
                }
                traceVerbose(worker, "transformed <= quoted '"+line+"' text");
                m.appendReplacement(sb, Matcher.quoteReplacement("\n"+line+" "+quoted));
            }
            m.appendTail(sb);
            res = sb.toString();
        }

        //
        // MAIN LINE-BY-LINE LOOP
        //
        traceVerbose(worker, "checking config line-by-line");
        String toptag = "";
        int numTrapsDev = 0;
        String[] lines = res.split("\n");
        StringBuilder sbin = new StringBuilder();
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String input = null;
            boolean silent = false;

            // Update toptag
            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = trimmed;
            }

            //
            // ' description '
            //
            if (line.contains(" description ")) {
                input = quoteDescription(toptag, line);
                silent = devTraceEnable;
            }

            //
            // errdisable
            //
            else if (toptag.startsWith("errdisable")) {
                input = line.replace("channel-misconfig (STP)", "channel-misconfig");
            }

            //
            // class-map * / match vlan *
            // class-map * / match vlan inner *
            // class-map * / match input vlan *
            //
            else if (toptag.startsWith("class-map ")
                     && trimmed.startsWith("match ") && trimmed.contains(" vlan")
                     && (match = getMatch(trimmed, "match .*vlan(?: inner)?[ ]+(.+)")) != null) {
                input = line.replace(match,match.replace(" ", ","));
            }

            //
            // ip nbar custom * udp|tcp *
            //
            else if (line.startsWith("ip nbar custom ")
                     && (match = getMatch(trimmed, "ip nbar custom \\S+ (?:udp|tcp) (.+?)(?: id \\d+)?")) != null) {
                input = line.replace(match,match.replace(" ", ","));
            }

            //
            // interface * / ntp broadcast key <key> destination <address>
            //
            else if (toptag.startsWith("interface ")
                     && trimmed.startsWith("ntp broadcast ") && trimmed.contains(" destination ")) {
                // Move destination address (list key) first.
                input = line.replaceFirst("ntp broadcast (.*?) (destination \\S+)", "ntp broadcast $2 $1");
            }

            //
            // interface * / service-policy in|out
            //
            else if (toptag.startsWith("interface ")
                     && (line.startsWith("  service-policy in ") || line.startsWith("  service-policy out "))) {
                input = line.replace("  service-policy in ", "  service-policy input ");
                input = input.replace("  service-policy out ", "  service-policy output ");
            }

            //
            // controller SONET *
            //
            else if (toptag.startsWith("controller SONET ")) {
                if (line.contains(" clock source ")) {
                    // controller SONET * / au-3 * / tug-2 * t1 * clock source
                    input = line.toLowerCase();
                } else {
                    // controller SONET * / sts-1 x - y mode sts-3c
                    input = line.replaceFirst("sts-1 (.*?) mode ", "sts-1 \"$1\" mode ");
                }
            }

            //
            // crypto pki server
            //
            else if (toptag.startsWith("crypto pki server ") && trimmed.startsWith("issuer-name ")) {
                input = line.replaceFirst("issuer-name (.*)", "issuer-name \"$1\"");
            }

            // crypto key pubkey-chain rsa / addressed-key * / address
            //
            else if (toptag.startsWith("crypto key pubkey-chain rsa") && line.startsWith("  address ")) {
                input = "";
            }

            //
            // crypto map * ipsec-isakmp / set peer * default
            //
            else if (toptag.startsWith("crypto map ") && line.startsWith(" set peer ") && line.contains(" default")) {
                input = line.replace(" set peer ", " set peer-default ").replace(" default", "");
            }

            //
            // crypto isakmp policy * / encr
            //
            else if (toptag.startsWith("crypto isakmp policy ") && line.startsWith(" encryption ")) {
                input = line.replace("encryption ", "encr ");
            }

            //
            // no crypto ikev2 policy default
            //
            else if ("no crypto ikev2 policy default".equals(trimmed)) {
                input = "crypto ikev2 policy-default-disabled";
            }

            //
            // ip explicit-path * / index
            //
            else if (toptag.startsWith("ip explicit-path ") && line.startsWith("ip explicit-path ")) {
                // insert missing 'index <value>' (not shown in running-config)
                int index = 0;
                boolean ipepmod = false;
                for (i = n + 1; i < lines.length; i++) {
                    if (lines[i].startsWith(" index ")
                        && (match = getMatch(lines[i], " index (\\d+) ")) != null) {
                        index = Integer.parseInt(match);
                    } else if (lines[i].startsWith(" next-address ")
                               || lines[i].startsWith(" exclude-address ")) {
                        ipepmod = true;
                        lines[i] = " index " + (++index) + lines[i];
                    } else if (!lines[i].trim().isEmpty()) {
                        break;
                    }
                }
                if (ipepmod) {
                    traceVerbose(worker, "transformed <= injected index(es) in '"+line+"'");
                }
            }

            //
            // ip access-list unordered standard|extended *
            //
            else if (ipACLunorderedRegex != null
                     && (line.startsWith("ip access-list extended ") || line.startsWith("ip access-list standard "))
                     && getMatch(trimmed, "^ip access-list (?:standard|extended) ("+ipACLunorderedRegex+")\\s*$")
                     != null) {
                input = line.replace("ip access-list ", "ip access-list unordered ");
            }

            //
            // ip access-list - ned-settings cisco-ios api access-list-resequence
            //
            else if (resequenceACL && line.startsWith("ip access-list extended ")) {
                sbin.append(lines[n]+"\n");
                for (n = n + 1; n < lines.length; n++) {
                    if ("!".equals(lines[n].trim())) {
                        break;
                    }
                    if (lines[n].trim().startsWith("remark ")) {
                        traceVerbose(worker, "transformed <= stripped '"+lines[n].trim()+"'");
                        continue;
                    }
                    if ((match = getMatch(lines[n], "^( \\d+) .*$")) != null) {
                        String stripped = lines[n].replace(match, "");
                        traceVerbose(worker, "transformed <= '"+lines[n]+"' to '"+stripped+"'");
                        sbin.append(stripped+"\n");
                    } else {
                        sbin.append(lines[n]+"\n");
                    }
                }
            }

            //
            // ipv6 access-list - ned-settings cisco-ios api new-ip-access-list
            //
            else if (newIpACL && line.startsWith("ipv6 access-list ")) {
                // Note: device trims sequence numbers spaced 10 from the previous
                sbin.append(lines[n]+"\n");
                int sequence = 0;
                for (n = n + 1; n < lines.length; n++) {
                    if ("!".equals(lines[n].trim())) {
                        break;
                    }
                    if ((match = getMatch(lines[n], "^ sequence (\\d+) ")) != null) {
                        sequence = Integer.parseInt(match);
                        sbin.append(lines[n]+"\n");
                    } else {
                        sequence = sequence + 10;
                        traceVerbose(worker, "transformed <= injected sequence number "+sequence+" in '"+lines[n]+"'");
                        sbin.append(" sequence "+sequence+lines[n]+"\n");
                    }
                }
            }

            //
            // ip access-list * / remark "<string>
            //
            else if (toptag.startsWith("ip access-list standard ") && line.startsWith(" remark ")
                     && line.startsWith(" remark \"") && !line.endsWith("\"")) {
                input = line.replace("\"", "\\\"");
            }

            //
            // ip source binding *
            //
            else if (trimmed.startsWith("ip source binding ")
                     && (match = getMatch(trimmed, "interface ([A-Za-z]+)\\d+")) != null) {
                String ifname = expandInterfaceName(match);
                input = line.replace("interface "+match, "interface "+ifname);
            }

            //
            // redirect server-group * / server ip * port *
            //
            else if (toptag.startsWith("redirect server-group ")
                     && line.startsWith(" server ip") && !line.contains(" port")) {
                input = " " + trimmed + " port 0";
            }

            //
            // logging discriminator *
            //
            else if (toptag.startsWith("logging discriminator ")) {
                String[] tokens = trimmed.split("(?= (?:severity|facility|mnemonics|msg-body))");
                input = tokens[0];
                for (int t = 1; t < tokens.length; t++) {
                    if ((match = getMatch(tokens[t], "\\S+ (?:drops|includes) (.+)")) != null) {
                        tokens[t] = tokens[t].replace(match, stringQuote(match));
                    }
                    input += (" "+tokens[t]);
                }
            }

            //
            // logging host *
            //
            else if (apiIosCommon && line.startsWith("logging host ")) {
                input = line.replace("logging host", "logging");
            }

            //
            // policy-map
            //
            else if (toptag.startsWith("policy-map ")) {

                // policy-map * / class * / random-detect drops 'precedence-based' name
                if (trimmed.startsWith("random-detect aggregate")) {
                    input = line.replace("random-detect aggregate", "random-detect precedence-based aggregate");
                } else if ("random-detect".equals(trimmed)) {
                    input = line.replace("random-detect", "random-detect precedence-based");
                } else if (line.contains(" mark-probability")) {
                    input = line.replace(" mark-probability", " mark-prob");
                } else if (trimmed.startsWith("set mpls exp topmost ")) {
                    input = line.replace("set mpls exp topmost ", "set mpls experimental topmost ");
                }

                // policy-map * / class * / police - insert missing [cir|bc|be]
                else if (trimmed.startsWith("police ")
                         && (hasPolice("cirmode") || hasPolice("cirflat"))
                         && !trimmed.startsWith("police cir ")
                         && !trimmed.startsWith("police rate ")
                         && !trimmed.startsWith("police aggregate ")
                         && !trimmed.contains(" exceed-action policed-dscp-transmit") // C35[56]0
                         && !trimmed.matches("police (\\d+) [kmg]?bps (\\d+) [kmg]?byte.*")) {
                    traceVerbose(worker, "attempting auto-insert of cir|bc|be in "+toptag+" / class * / "+trimmed);
                    input = line.replaceFirst("police (\\d+) (\\d+) (\\d+)", "police cir $1 bc $2 be $3");
                    input = input.replaceFirst("police (\\d+) (\\d+)", "police cir $1 bc $2");
                    input = input.replaceFirst("police (\\d+)", "police cir $1");
                }
            }

            //
            // spanning-tree mst configuration / instance * vlan <val>, <val2>
            //
            else if (toptag.startsWith("spanning-tree mst configuration")
                     && findString(" instance [0-9]+ vlan ", line) >= 0) {
                input = line.replace(", ", ",");
            }

            //
            // monitor session * filter vlan *
            // monitor session * source vlan *
            // monitor session * source remote vlan *
            // monitor session * destination remote vlan *
            // monitor session * type local|capture / source vlan *
            //
            else if (toptag.startsWith("monitor session ") && trimmed.contains(" vlan ")) {
                input = line.replace(" , ",",").replace(" - ","-");
            }

            // vlan group * vlan-list *
            else if (line.startsWith("vlan group ") && line.contains(", ")) {
                input = line.replace(", ", ",");
            }

            // define interface-range *
            //
            else if (line.startsWith("define interface-range ")) {
                input = line.replace(" , ",",").replace(" - ","-");
            }

            //
            // l2tp-class / password encryption aes
            //
            else if (toptag.startsWith("l2tp-class ") && "password encryption aes".equals(trimmed)) {
                input = "";
            }

            //
            // crypto keyring / ! Keyring unusable for nonexistent vrf
            //
            else if (toptag.startsWith("crypto keyring ")
                     && trimmed.contains("! Keyring unusable for nonexistent vrf")) {
                input = line.replace("! Keyring unusable for nonexistent vrf", "");
            }

            //
            // parameter-map type regexp * / pattern *
            //
            else if (toptag.startsWith("parameter-map type regex ")
                     && trimmed.startsWith("pattern ")
                     && (match = getMatch(trimmed, "pattern (.*)")) != null) {
                input = line.replace(match, stringQuote(match));
            }

            //
            // router bgp * / address-family ipv4 vrf *
            //
            else if (toptag.startsWith("router bgp ")
                     && (trimmed.startsWith("address-family ipv4 vrf ")
                         || trimmed.startsWith("address-family ipv6 vrf "))) {
                input = line.replaceFirst(" vrf ", " unicast vrf ");
            }

            //
            // track * ipv6 route
            //
            else if (toptag.startsWith("track ") && trimmed.contains(" ipv6 route :: ")) {
                input = line.replace(" :: ", " ::/0 ");
            }

            //
            // et-analytics / inactive-timeout
            //
            else if (toptag.startsWith("et-analytics")
                     && trimmed.startsWith("inactive_timeout ")) {
                input = line.replace("inactive_timeout", "inactive-timeout");
            }

            // interface * + interface * / stackwise-virtual
            else if (autoStackwiseVirtualPatch && toptag.startsWith("interface ")
                     && line.startsWith(" interface ") && res.contains(" stackwise-virtual ")) {
                input = line.substring(1);
            }

            //
            // snmp-server host * and ned-settings cisco-ios api new-snmp-server-host true
            //
            else if (newSnmpServerHost
                     && toptag.startsWith("snmp-server host ")
                     && (match = getMatch(trimmed, "snmp-server host \\S+ vrf \\S+ (\\S+)")) != null
                     && (!"informs".equals(match) && !"traps".equals(match))) {
                input = line.replace(match, "traps " + match);
            } else if (newSnmpServerHost
                     && toptag.startsWith("snmp-server host ")
                     && (match = getMatch(trimmed, "snmp-server host \\S+ (\\S+)")) != null
                     && (!"informs".equals(match) && !"traps".equals(match))) {
                input = line.replace(match, "traps " + match);
            }

            //
            // snmp-server enable traps
            //
            else if (apiSnmpServerEnableAllTraps != 0 && line.startsWith("snmp-server enable traps ")) {
                numTrapsDev++;
                continue;
            }

            //
            // snmp-server host *
            //
            else if (line.startsWith("snmp-server host ") && line.contains(" inform ")) {
                input = line.replace(" inform ", " informs ");
            } else if (line.startsWith("snmp-server host ") && line.contains(" trap ")) {
                input = line.replace(" trap ", " traps ");
            }

            //
            // string-quote strings
            //
            else if (toptag.startsWith("snmp-server ")
                     && (match = getMatch(trimmed, "snmp-server contact (.+)")) != null) {
                input = line.replace(" contact "+match, " contact "+stringQuote(match));
            } else if (toptag.startsWith("snmp-server ")
                       && (match = getMatch(trimmed, "snmp-server location (.+)")) != null) {
                input = line.replace(" location "+match, " location "+stringQuote(match));
            } else if (toptag.startsWith("alias ")
                       && (match = getMatch(trimmed, "alias \\S+ \\S+ (.*)")) != null) {
                input = line.replace(match, stringQuote(match));
            } else if (toptag.startsWith("crypto isakmp key ")
                       && (match = getMatch(trimmed, "crypto isakmp key (\\S+) "
                                            +"(?:address|hostname|address ipv6) \\S+")) != null) {
                input = line.replace(match, stringQuote(match));
            } else if (toptag.startsWith("crypto pki profile enrollment ")
                       && trimmed.startsWith("authentication command ")
                       && (match = getMatch(trimmed, "authentication command (.*)")) != null) {
                input = line.replace(match, stringQuote(match));
            } else if (toptag.startsWith("utd ")
                       && (match = getMatch(trimmed, "signature id \\d+ comment (.*)")) != null) {
                input = line.replace(match, stringQuote(match));
            } else if (toptag.startsWith("utd ")
                       && (match = getMatch(trimmed, "^(?:content )?text (.*)")) != null) {
                input = line.replace(match, stringQuote(match));
            }
            else if (toptag.startsWith("chat-script ") && trimmed.startsWith("chat-script ")
                     && (match = getMatch(trimmed, "chat-script \\S+ (.+)")) != null) {
                input = line.replace(match, stringQuote(match));
            }
            else if (toptag.startsWith("kron policy-list ") && trimmed.startsWith("cli ")
                     && (match = getMatch(trimmed, "cli (.+)")) != null) {
                input = line.replace(match, stringQuote(match));
            }
            else if (toptag.startsWith("crypto pki trustpoint ") && trimmed.startsWith("subject-name ")
                     && (match = getMatch(trimmed, "subject-name (.+)")) != null) {
                input = line.replace(match, stringQuote(match));
            }

            //
            // password-quote strings
            //
            else if (toptag.startsWith("voice translation-rule ") && trimmed.startsWith("rule ")
                     && (group = getMatches(trimmed, "rule (\\d+) ([/].*[/]) ([/].*[/])")) != null
                     && Integer.parseInt(group[0]) == 3) {
                // voice translation-rule * / rule
                input = " rule "+group[1]+" "+passwordQuote(group[2])+" "+passwordQuote(group[3]);
            }

            //
            // quote password
            //
            else if (trimmed.startsWith("crypto isakmp key 6 ")
                     && (match = getMatch(trimmed, "crypto isakmp key 6 (\\S+) "
                                          +"(?:address|hostname|address ipv6) \\S+")) != null) {
                // crypto isakmp key 6
                input = line.replace(match, passwordQuote(match));

            } else if (trimmed.startsWith("authentication-key 6 ")
                       && (match = getMatch(trimmed, "authentication-key 6 (\\S+)")) != null) {
                // router lisp * / authentication-key 6
                input = line.replace(match, passwordQuote(match));

            } else if (trimmed.startsWith("ipv4 etr map-server ")
                       && (match = getMatch(trimmed, "ipv4 etr map-server \\S+ key 6 (\\S+)")) != null) {
                // router lisp * / ipv4 etr map-server
                input = line.replace(match, passwordQuote(match));

            } else if (toptag.startsWith("crypto ") && trimmed.startsWith("pre-shared-key ")
                       && (match = getMatch(trimmed, "pre-shared-key(?: local| remote)? 6 (\\S+)")) != null) {
                // crypto ikev2 keyring / peer * / pre-shared-key
                input = line.replace(match, passwordQuote(match));

            } else if (toptag.startsWith("crypto ") && trimmed.startsWith("aaa authorization group ")
                       && (match = getMatch(trimmed,"aaa authorization group (?:psk|eap) list \\S+ password 6 (\\S+)"))
                       != null) {
                // crypto ikev2 profile * / aaa authorization group
                input = line.replace(match, passwordQuote(match));

            } else if (toptag.startsWith("crypto ") && trimmed.startsWith("authentication ")
                       && (match = getMatch(trimmed, "authentication (?:local|remote) pre-share key 6 (\\S+)"))
                       != null) {
                // crypto ikev2 profile * / authentication
                input = line.replace(match, passwordQuote(match));

            } else if (toptag.startsWith("crypto isakmp client configuration group")
                       && (match = getMatch(trimmed, "\\s+key 6 (\\S+)")) != null) {
                // crypto isakmp client configuration group * /
                input = line.replace(match, passwordQuote(match));

            } else if (toptag.startsWith("crypto keyring ") && trimmed.startsWith("pre-shared-key address ")
                       && (match = getMatch(trimmed, "pre-shared-key address \\S+(?: \\S+)? key 6 (\\S+)")) != null) {
                // crypto keyring * / pre-shared-key address
                input = line.replace(match, passwordQuote(match));
            }
            else if (toptag.startsWith("router bgp ")
                     && (match = getMatch(trimmed, "neighbor \\S+ password(?: [0-7])? (.*)")) != null) {
                // router bgp * / neighbor * password *
                input = line.replace(match, passwordQuote(match));
            }
            else if (toptag.startsWith("router bgp ") && trimmed.contains(" remove-private-AS")) {
                input = line.replace(" remove-private-AS", " remove-private-as");
            }

            else if ((match = getMatch(line, "^ip ftp password(?: [0-7])? (.+)")) != null) {
                // ip ftp password
                input = line.replace(match, passwordQuote(match));
            }

            //
            // transform single lines
            //
            else if (trimmed.startsWith("ip domain-name")) {
                input = line.replace("ip domain-name", "ip domain name");
            } else if (trimmed.startsWith("ip domain-list")) {
                input = line.replace("ip domain-list", "ip domain list");
            } else if (line.startsWith("ip domain-lookup")) {
                input = line.replace("ip domain-lookup", "ip domain lookup");
            } else if (trimmed.startsWith("no ip domain-lookup")) {
                input = line.replace("no ip domain-lookup", "no ip domain lookup");
            } else if ("line con 0".equals(trimmed)) {
                input = "line console 0";
            } else if (trimmed.startsWith("aaa authorization ")) {
                input = line.replaceAll("aaa authorization (.*)local if-authenticated",
                                        "aaa authorization $1if-authenticated local");
            }

            //
            // transform no-list lists/leaves
            //
            if (trimmed.startsWith("no ip forward-protocol udp ")) {
                input = line.replaceAll("no ip forward-protocol udp (\\S+)",
                                        "ip forward-protocol udp $1 disabled");
            } else if (trimmed.startsWith("no cable cm-status enable ")) {
                input = line.replace("no cable cm-status enable ",
                                     "cable cm-status enable no-list ");
            } else if (trimmed.startsWith("no passive-interface ")) {
                input = line.replace("no passive-interface ", "disable passive-interface ");
                if (input.contains("Control Plane Interface")) {
                    input = input.replace("Control Plane Interface", "\"Control Plane Interface\"");
                }
            } else if (trimmed.startsWith("no network-clock-participate wic ")) {
                input = line.replace("no network-clock-participate wic ",
                                     "network-clock-participate wic wic-disabled ");
            } else if (line.startsWith("no network-clock-participate slot ")) {
                input = line.replace("no network-clock-participate slot ",
                                     "network-clock-participate slot slot-disabled ");
            } else if (trimmed.startsWith("no wrr-queue random-detect ")) {
                input = line.replace("no wrr-queue random-detect ",
                                     "no-list wrr-queue random-detect ");
            } else if (trimmed.startsWith("no rcv-queue random-detect ")) {
                input = line.replace("no rcv-queue random-detect ",
                                     "no-list rcv-queue random-detect ");
            } else if (trimmed.startsWith("no spanning-tree vlan ")) {
                input = line.replace("no spanning-tree vlan ",
                                     "spanning-tree vlan no-list ");
            } else if (trimmed.startsWith("no mac-address-table learning vlan ")) {
                input = line.replace("no mac-address-table learning vlan ",
                                     "mac-address-table learning vlan no-list ");
            } else if (trimmed.startsWith("no mac address-table learning vlan ")) {
                input = line.replace("no mac address-table learning vlan ",
                                     "mac address-table learning vlan no-list ");
            } else if (trimmed.startsWith("no ip igmp snooping vlan ")) {
                input = line.replace("no ip igmp snooping vlan ",
                                     "ip igmp snooping vlan no-list ");
            } else if (trimmed.startsWith("no ip next-hop-self eigrp ")) {
                input = line.replace("no ip next-hop-self eigrp ",
                                     "ip next-hop-self eigrp no-list ");
            } else if (trimmed.startsWith("no ip split-horizon eigrp ")) {
                input = line.replace("no ip split-horizon eigrp ",
                                     "ip split-horizon eigrp no-list ");
            } else if (toptag.startsWith("parameter-map type ") && trimmed.startsWith("no application-inspect ")) {
                input = line.replace("no application-inspect ",
                                     "application-inspect no-list ");
            } else if (trimmed.startsWith("no hw-module module ") && trimmed.endsWith(" logging onboard")) {
                input = line.replace("no hw-module module ", "hw-module module no-list ");
            } else if (line.startsWith("no power enable module ")) {
                input = line.replace("no power enable module ", "power enable module no-list ");
            }

            // diagnostic monitor Module * disable
            else if (line.startsWith("no diagnostic monitor Module ")) {
                input = line.substring(3).trim() + " disable";
            }

            //
            // transform no-enable leaves
            //
            else if (trimmed.startsWith("no cts server test ") && trimmed.endsWith(" enable")) {
                input = line.replace("no ", "").replace(" enable", " no-enable");
            }

            //
            // strip single lines
            //
            else if ("boot-start-marker".equals(trimmed) || "boot-end-marker".equals(trimmed)) {
                lines[n] = ""; // silent
            } else if (trimmed.startsWith("radius-server source-ports ")) {
                input = "";
            } else if ("no logging console".equals(trimmed)) {
                input = ""; // 'show logging xml' adds 'logging console <level>'
            } else if (trimmed.startsWith("license udi")) {
                input = ""; // not config
            } else if (trimmed.startsWith("! Incomplete")) {
                input = ""; // comments
            } else if (trimmed.startsWith("ip msdp ") && trimmed.endsWith(" cache-sa-state")) {
                input = ""; // config? (can't be disabled)
            } else if ("cpp system-default".equals(toptag)) {
                input = ""; //config? (can't be disabled)
            }

            //
            // strip lines not needed with current YANG
            //
            else if (line.startsWith("no mls rate-limit ")) {
                input = "";
            } else if (toptag.startsWith("line ") && line.startsWith(" no vacant-message")) {
                input = "";
            } else if (toptag.startsWith("line ") && line.startsWith(" no refuse-message")) {
                input = "";
            } else if ("no platform punt-keepalive settings".equals(trimmed)) {
                input = "";
            } else if ("no logging buffered".equals(trimmed)) {
                input = "";
            } else if ("no scheduler max-sched-time".equals(trimmed) || "no scheduler allocate".equals(trimmed)) {
                input = "";
            } else if (line.startsWith("ap hyperlocation ble-beacon ")) {
                input = "";
            } else if (toptag.startsWith("voice register global") && "default mode".equals(trimmed)) {
                input = "";
            } else if (toptag.startsWith("presence") && "presence enable".equals(trimmed)) {
                input = "";
            }

            // Strip deprecated 'no XXX' lines:
            // no scheduler allocate
            // interface * / no mls qos trust
            else if (line.startsWith("no scheduler allocate") && trimmed.endsWith(" allocate")) {
                input = "";
            } else if (toptag.startsWith("interface ") && "no mls qos trust".equals(trimmed)) {
                input = "";
            } else if (line.startsWith("aaa accounting ") && line.contains(" start-stop tacacs+")) {
                input = line.replace(" start-stop tacacs+", " start-stop group tacacs+");
            }

            // INPUT CAPS
            // cable profile ssd * / ssd
            // interface VLAN*
            // source VLAN*
            //
            else if (toptag.startsWith("cable profile ssd ") && trimmed.startsWith("ssd ")) {
                input = line.replace(" HTTP ", " http ").replace(" TFTP ", " tftp ");
            } else if (line.startsWith("interface VLAN")) {
                input = line.replace("interface VLAN", "interface Vlan");
            } else if (line.contains("interface VLAN") && getMatch(line, "interface VLAN(\\d+)\\s*$") != null) {
                input = line.replace("interface VLAN", "interface Vlan");
            } else if (line.contains("source VLAN") && getMatch(line, "source VLAN(\\d+)\\s*$") != null) {
                input = line.replace("source VLAN", "source Vlan");
            }

            //
            // Convert space to comma for range-list-syntax leaf-list's
            //
            // cable profile service-group * / load-balance docsis-group * profile * / downstream sg-channel *
            else if (toptag.startsWith("cable profile service-group ") && line.startsWith("  downstream sg-channel ")
                     && (match = getMatch(line, " sg-channel ([0-9 -]+)$")) != null) {
                input = line.replace(match, match.replace(" ", ","));
            }
            if (input == null) {
                String[] spaceToComma = {
                    // Fix cable rf-channels channel-list x-y z bandwidth-percent
                    // Fix cable rf-channels controller ? channel-list x-y z bandwidth-percent
                    " channel-list (.+) bandwidth-percent",
                    " downstream sg-channel (.+) profile \\S+",

                    " downstream sg-channel (.+) rf-bandwidth-percent \\d+",
                    " downstream sg-channel .+ profile \\S+ upstream (.+)"
                };
                for (int j = 0; j < spaceToComma.length; j++) {
                    Pattern p = Pattern.compile(spaceToComma[j]);
                    Matcher m = p.matcher(line);
                    if (m.find()) {
                        if (input == null) {
                            input = line;
                        }
                        String replacement = m.group(1).replace(" ", ","); // type leaf-list
                        if (j >= 2) {
                            replacement = "\"" + m.group(1) + "\""; // type string
                        }
                        input = input.substring(0,m.start(1))+replacement+input.substring(m.end(1));
                    }
                }
            }

            //
            // Transform lines[n] -> XXX
            //
            if (input != null && !input.equals(lines[n])) {
                if (input.isEmpty()) {
                    if (!silent) {
                        traceVerbose(worker, "transformed <= stripped '"+trimmed+"'");
                    }
                    continue;
                }
                if (!silent) {
                    traceVerbose(worker, "transformed <= '"+trimmed+"' to '"+input.trim()+"'");
                }
                sbin.append(input+"\n");
            } else if (lines[n] != null && !lines[n].isEmpty()) {
                sbin.append(lines[n]+"\n");
            }

        } // for loop

        //
        // ned-settings cisco-ios api snmp-server-enable-all-traps != 0
        //
        handleAllTrapsInput(worker, numTrapsDev, sbin);

        //
        // APPEND TRANSFORMATIONS (may add, delete or reorder lines)
        //
        res = sbin.toString();
        sbin = new StringBuilder();
        traceVerbose(worker, "checking config line-by-line #2");
        lines = res.split("\n");
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String nexttrim = (n + 1 < lines.length) ? lines[n+1].trim() : "";
            boolean split = false;

            // Update toptag
            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = trimmed; // Strip '\r'
            }

            //
            //  ned-settings cisco-ios auto for interface *
            //
            if (session != null && line.startsWith("interface ")) {
                // Add interface * toptag line
                sbin.append(line+"\n");

                // cisco-ios auto interface-switchport-status true
                if (autoInterfaceSwitchportStatus
                    && (line.contains("Ethernet") || line.contains("Port-channel"))) {
                    res = print_line_exec(worker, "show " + trimmed + " switchport | i Switchport");
                    if (res.contains("Switchport: Enabled")) {
                        traceVerbose(worker, "transformed <= inserted 'switchport' from 'show "+trimmed+"'");
                        sbin.append(" switchport\n");
                    } else if (res.contains("Switchport: Disabled")) {
                        traceVerbose(worker, "transformed <= inserted 'no switchport' from 'show "+trimmed+"'");
                        sbin.append(" no switchport\n");
                    }
                }

                continue;
            }

            //
            // shutdown vlan *
            //
            if (line.startsWith("shutdown vlan ") && (match = getMatch(line, "vlan (\\d+)")) != null) {
                sbin.append("vlan "+match+"\n shutdown\nexit\n");
                traceVerbose(worker, "transformed <= shutdown vlan "+match+" to vlan "+match+" / shutdown");
                continue;
            }

            //
            // interface * / switchport trunk allowed vlan
            //
            else if (toptag.startsWith("interface ")
                     && trimmed.startsWith("switchport trunk allowed vlan ")
                     && nexttrim.startsWith("switchport trunk allowed vlan add ")) {
                traceVerbose(worker, "transformed <= joined '"+toptag+"' switchport trunk allowed vlan entries");
                sbin.append(" " + trimmed);
                for (n = n + 1; n < lines.length; n++) {
                    trimmed = lines[n].trim();
                    if ("switchport trunk allowed vlan add none".equals(trimmed)) {
                        break;
                    }
                    if ((match = getMatch(trimmed, "switchport trunk allowed vlan add (.*)")) == null) {
                        break;
                    }
                    sbin.append("," + match);
                }
                sbin.append("\n");
                if ("switchport trunk allowed vlan add none".equals(trimmed)) {
                    continue; // Strip invalid line
                }
                // fall through to add break line
            }

            //
            // interface * / ipv6 nd inspection vlan
            //
            else if (toptag.startsWith("interface ")
                     && trimmed.startsWith("ipv6 nd inspection vlan ")
                     && nexttrim.startsWith("ipv6 nd inspection vlan add ")) {
                traceVerbose(worker, "transformed <= joined '"+toptag+"' ipv6 nd inspection vlan entries");
                sbin.append(" " + trimmed);
                for (n = n + 1; n < lines.length; n++) {
                    trimmed = lines[n].trim();
                    if ((match = getMatch(trimmed, "ipv6 nd inspection vlan add (.*)")) == null) {
                        break;
                    }
                    sbin.append("," + match);
                }
                sbin.append("\n");
                // fall through to add break line
            }

            //
            // interface * / service instance * / encapsulation dot1q
            //
            else if (toptag.startsWith("interface ")
                     && line.startsWith(" service instance ")
                     && nexttrim.startsWith("encapsulation dot1q ")) {
                sbin.append(" " + trimmed + "\n"); // 'service instance' line
                sbin.append("  " + nexttrim);
                boolean joined = false;
                for (n = n + 2; n < lines.length; n++) {
                    trimmed = lines[n].trim();
                    if ((match = getMatch(trimmed, "encapsulation dot1q add (.+)")) == null) {
                        break;
                    }
                    joined = true;
                    sbin.append("," + match);
                }
                sbin.append("\n");
                if (joined) {
                    traceVerbose(worker, "transformed <= joined '"+toptag+" / "+trimmed
                                 +"' encapsulation dot1q entries");
                }
                // fall through to add break line
            }

            //
            // interface * / l2protocol *
            // interface * / service instance * ethernet / l2protocol *
            //
            else if (toptag.startsWith("interface ") && line.startsWith(" l2protocol ")) {
                split = splitLines(sbin, " ", trimmed.split(" +"), 2, 0);
            }
            else if (toptag.startsWith("interface ") && line.startsWith("  l2protocol ")) {
                split = splitLines(sbin, "  ", trimmed.split(" +"), 2, 0);
            }

            //
            // mac-address-table learning vlan no-list
            // mac address-table learning vlan no-list
            //
            else if (line.startsWith("mac-address-table learning vlan no-list ")) {
                split = splitRangeList(sbin, line);
            } else if (line.startsWith("mac address-table learning vlan no-list ")) {
                split = splitRangeList(sbin, line);
            }

            //
            // interface * / vlan-range *
            //
            else if (toptag.startsWith("interface ")
                     && line.startsWith(" vlan-range ")
                     && (line.contains("-") || line.contains(","))) {

                StringBuffer content = new StringBuffer();
                for (n = n + 1; n < lines.length; n++) {
                    if (!lines[n].startsWith("  ")) {
                        n = n - 1;
                        break;
                    }
                    content.append(lines[n]+"\n");
                }

                int num = 0;
                String[] ranges = trimmed.substring(11).trim().split(",");
                for (int r = 0; r < ranges.length; r++) {
                    Pattern p = Pattern.compile("(\\d+)(?:[-](\\d+))?");
                    Matcher m = p.matcher(ranges[r]);
                    if (!m.find()) {
                        continue;
                    }
                    int start = Integer.parseInt(m.group(1));
                    int end = m.group(2) != null ? Integer.parseInt(m.group(2)) : start;
                    for (int v = start; v <= end; v++) {
                        sbin.append(" vlan-range "+v+"\n");
                        sbin.append(content);
                        num++;
                    }
                }
                traceVerbose(worker, "transformed <= split '"+trimmed+"' in "+num);
                continue;
            }

            //
            // route-map * / set extcommunity rt
            //
            else if (toptag.startsWith("route-map ")
                     && trimmed.matches("^set extcommunity rt [0-9: ]+ additive$")) {
                // Join 'set extcommunity rt ... additive' lines, split by device (e.g. asr1k)
                traceVerbose(worker, "transformed <= joined '"+toptag+"' set extcommunity rt additive entries");
                sbin.append(" set extcommunity rt");
                for (; n < lines.length; n++) {
                    trimmed = lines[n].trim();
                    if ((match = getMatch(trimmed, "set extcommunity rt( [0-9: ]+) additive$")) == null) {
                        break;
                    }
                    sbin.append(match);
                }
                sbin.append(" additive\n");
                continue;
            }

            //
            // route-map * / match interface
            //
            else if (toptag.startsWith("route-map ")
                     && trimmed.startsWith("match interface ")
                     && nexttrim.startsWith("match interface ")) {
                // Join lines
                traceVerbose(worker, "transformed <= joined '"+toptag+"' match interface entries");
                sbin.append(" "+trimmed);
                for (n = n + 1; n < lines.length; n++) {
                    trimmed = lines[n].trim();
                    if ((match = getMatch(trimmed, "match interface( .+)")) == null) {
                        break;
                    }
                    sbin.append(match);
                }
                sbin.append("\n");
                continue;
            }

            //
            // aaa accounting
            //
            else if (line.startsWith("aaa accounting ") && nexttrim.startsWith("action-type")) {
                traceVerbose(worker, "transformed <= compacted '"+trimmed+"'");
                // action-type
                line = trimmed + nexttrim.replace("action-type", "");
                nexttrim = (++n + 1 < lines.length) ? lines[n+1].trim() : "";
                // optional broadcast
                if ("broadcast".equals(nexttrim)) {
                    line += " broadcast";
                    nexttrim = (++n + 1 < lines.length) ? lines[n+1].trim() : "";
                }
                // optional group
                if (nexttrim.startsWith("group ")) {
                    line += (" " + nexttrim);
                    nexttrim = (++n + 1 < lines.length) ? lines[n+1].trim() : "";
                }
                // optional group2
                if (nexttrim.startsWith("group ")) {
                    line += (" " + nexttrim);
                    n++;
                }
                sbin.append(line + "\n");
                continue;
            }

            //
            // call-home * / profile * / [no ]active
            // call-home * / profile * / [no ]reporting smart-call-home-data
            //
            else if (toptag.startsWith("call-home") && line.startsWith(" profile ")) {
                sbin.append(line+"\n");
                String callprof = print_line_exec(worker, "show call-home "+trimmed);
                if (!isExecError(callprof)) {
                    if (callprof.contains("Profile status: ACTIVE")) {
                        sbin.append("  active\n");
                    } else {
                        sbin.append("  no active\n");
                    }
                    if (callprof.contains("Smart Licensing")) {
                        sbin.append("  reporting smart-licensing-data\n");
                    } else {
                        sbin.append("  no reporting smart-licensing-data\n");
                    }
                }
                continue;
            }

            //
            // monitor session * source vlan *
            // monitor session * type erspan-source / source vlan *
            //
            else if (toptag.startsWith("monitor session ")
                     && line.contains(" source vlan ")
                     && (group = getMatches(line, "((?:monitor session \\d+)? source vlan )(\\S+)( \\S+)?"))
                     != null) {
                String suffix = group[3] != null ? group[3] : "";
                String[] vlans = group[2].split(",");
                for (i = 0; i < vlans.length; i++) {
                    String[] entry;
                    if ((entry = getMatches(vlans[i], "(\\d+)-(\\d+)")) != null) {
                        split = true;
                        int start = Integer.parseInt(entry[1]);
                        int end = Integer.parseInt(entry[2]);
                        for (int j = start; j <= end; j++) {
                            sbin.append(group[1] + j + suffix + "\n");
                        }
                    } else {
                        sbin.append(group[1] + vlans[i] + suffix + "\n");
                    }
                }
                if (split || vlans.length > 1) {
                    traceVerbose(worker, "transformed <= split '"+trimmed+"'");
                }
                continue;
            }

            //
            // monitor session * source|destination interface *
            // monitor session * type local / source|destination interface *
            // monitor session * type capture / source interface *
            // monitor session * type [e]rspan-source / source|destination interface *
            //
            else if (toptag.startsWith("monitor session ")
                     && (group = getMatches(line, "((?:monitor session \\d+)? (?:source|destination) interface) (.*)"))
                     != null) {
                String suffix;
                String ifBuf = group[2];
                if ((suffix = getMatch(ifBuf, "( rx| tx| both|(?: encapsulation .*)|(?: ingress vlan .*))")) != null) {
                    ifBuf = ifBuf.replace(suffix, "");
                } else {
                    suffix = "";
                }
                String[] interfaces = ifBuf.split(" , ");
                for (i = 0; i < interfaces.length; i++) {
                    String[] entry;
                    if ((entry = getMatches(interfaces[i].trim(), "(\\S+)/(\\d+) - (\\d+)")) != null) {
                        split = true;
                        int start = Integer.parseInt(entry[2]);
                        int end = Integer.parseInt(entry[3]);
                        for (int j = start; j <= end; j++) {
                            sbin.append(group[1] + " " + entry[1] + "/" + j + suffix + "\n");
                        }
                    } else {
                        sbin.append(group[1] + " " + interfaces[i].trim() + suffix + "\n");
                    }
                }
                if (split || interfaces.length > 1) {
                    traceVerbose(worker, "transformed <= split '"+trimmed+"'");
                }
                continue;
            }

            // monitor session * filter address-type
            else if (toptag.startsWith("monitor session ") && trimmed.contains("filter address-type")
                     && !trimmed.endsWith(" rx") && !trimmed.endsWith(" tx")) {
                sbin.append(trimmed+" rx\n");
                sbin.append(trimmed+" tx\n");
                continue;
            }

            //
            // qos map dscp policed * to dscp
            // qos map dscp * to tx-queue
            // qos map dscp * to cos
            // qos map cos * to dscp
            //
            else if (trimmed.startsWith("qos map dscp policed ")) {
                String[] tokens = trimmed.split(" +");
                split = splitLines(sbin, "", tokens, 4, 3);
            }
            else if (trimmed.startsWith("qos map ") && trimmed.contains(" to ")) {
                String[] tokens = trimmed.split(" +");
                split = splitLines(sbin, "", tokens, 3, 3);
            }

            //
            // mls qos map policed-dscp *
            //
            else if (trimmed.startsWith("mls qos map policed-dscp ") && trimmed.contains(" to ")) {
                String[] tokens = trimmed.split(" +");
                split = splitLines(sbin, "", tokens, 4, 2);
            }

            //
            // ip name-server [vrf <vrf>] <address 1> .. [address N]
            //
            else if (trimmed.startsWith("ip name-server ")) {
                String[] tokens = trimmed.split(" +");
                if ("vrf".equals(tokens[2])) {
                    split = splitLines(sbin, "", tokens, 4, 0);
                } else {
                    split = splitLines(sbin, "", tokens, 2, 0);
                }
            }

            //
            // router isis * / purge-transmit strict
            //
            else if (toptag.startsWith("router isis") && "purge-transmit strict".equals(trimmed)) {
                sbin.append(" purge-transmit strict level-1\n");
                sbin.append(" purge-transmit strict level-2\n");
                continue;
            }

            //
            // router isis * / no hello padding multi-point
            // router isis * / no hello padding point-to-point
            //
            else if (toptag.startsWith("router isis") && "no hello padding multi-point".equals(trimmed)
                     && "no hello padding point-to-point".equals(nexttrim)) {
                sbin.append(" no hello padding\n");
                n = n + 1; // skip 'no hello padding point-to-point'
                traceVerbose(worker, "transformed <= router isis hello padding to old IOS API");
                continue;
            }

            //
            // router ospf * / discard-route
            //
            else if (toptag.startsWith("router ospf ") && "no discard-route".equals(trimmed)) {
                sbin.append(" discard-route external disabled\n");
                sbin.append(" discard-route internal disabled\n");
                continue;
            } else if (toptag.startsWith("router ospf ") && "no discard-route external".equals(trimmed)) {
                sbin.append(" discard-route external disabled\n");
                continue;
            } else if (toptag.startsWith("router ospf ") && "no discard-route internal".equals(trimmed)) {
                sbin.append(" discard-route internal disabled\n");
                continue;
            }

            //
            // table-map * / map from <range> to <uint8>
            //
            else if (toptag.startsWith("table-map ") && trimmed.startsWith("map from ")) {
                String[] tokens = trimmed.split(" +");
                split = splitLines(sbin, " ", tokens, 2, 2);
            }

            //
            // line * / exec-timeout 10 0
            //
            else if (toptag.startsWith("line ") && line.startsWith("line ")) {
                traceVerbose(worker, "transformed <= injected: 'exec-timeout 10 0' first in "+trimmed);
                sbin.append(line+"\n"+" exec-timeout 10 0\n");
                continue;
            }

            //
            // policy-map * / class * / police cir - with cirflat
            //
            else if (hasPolice("cirflat") && toptag.startsWith("policy-map ")
                     && trimmed.startsWith("police cir ") && nexttrim.contains("-action ")) {
                traceVerbose(worker, "transformed <= flattened '"+toptag+"' "+trimmed+" actions");
                sbin.append(line.replace(trimmed,"") + trimmed);
                for (; n < lines.length - 1; n++) {
                    if (!lines[n+1].contains("-action ")) {
                        break;
                    }
                    sbin.append(" "+lines[n+1].trim());
                }
                sbin.append("\n");
                continue;
            }

            //
            // policy-map * / class * / police cir - with cirmode
            //
            else if (hasPolice("cirmode") && toptag.startsWith("policy-map ")
                     && trimmed.startsWith("police cir ") && trimmed.contains("-action ")) {
                traceVerbose(worker, "transformed <= inserted ! after flat syntax '"+trimmed+"'");
                sbin.append(line+"\n  !\n");
                continue;
            }

            //
            // policy-map * / class * / random-detect ? values
            //
            else if (toptag.startsWith("policy-map ")
                     && line.startsWith("  random-detect") && line.contains(" values ")) {
                Pattern p = Pattern.compile("(  random-detect \\S+ values )(\\d+ .?\\d+)( minimum-thresh .+)");
                Matcher m = p.matcher(line);
                if (m.find()) {
                    traceVerbose(worker, "transformed <= split '"+m.group(0)+"'");
                    String[] values = m.group(2).split("[ ]+");
                    for (int v = 0; v < values.length; v++) {
                        sbin.append(m.group(1)+values[v]+m.group(3)+"\n");
                    }
                    continue;
                }
            }

            // crypto keyring * / rsa-pubkey address *
            else if (toptag.startsWith("crypto keyring ") && line.startsWith("  rsa-pubkey ")) {
                sbin.append(line.substring(1));
                continue;
            }

            // crypto key pubkey-chain rsa
            //  addressed-key *
            //   key-string
            //    aaa bbb ccc
            //    ... ... ...
            //    xxx yyy zzz
            //   quit
            // OR:
            // crypto keyring * / rsa-pubkey address * / key-string
            else if (line.startsWith("  key-string")
                     && (toptag.startsWith("crypto key pubkey-chain rsa") || toptag.startsWith("crypto keyring "))) {
                StringBuilder keysb = new StringBuilder();
                for (n = n + 1; n < lines.length; n++) {
                    if ("quit".equals(lines[n].trim())) {
                        break;
                    }
                    keysb.append(lines[n]+"\n");
                }
                final String keyString = keysb.toString();
                if (keyString.trim().isEmpty()) {
                    traceDev(worker, "ignored empty key-string in '"+line+"'");
                } else {
                    traceVerbose(worker, "transformed <= quoted '"+line+"' key-string");
                    sbin.append("  key-string "+stringQuote(keyString)+"\n  quit\n");
                }
                continue;
            }

            //
            // Log or add if not split
            //
            if (split) {
                traceVerbose(worker, "transformed <= split '"+trimmed+"'");
            } else {
                sbin.append(lines[n]+"\n");
            }
        }


        //
        // Split line ranges into multiple single lines with config, e.g. line 0/2/15 0/3/0 or line vty 2 7
        //
        res = sbin.toString();
        res = modifyInputExpandLine(worker, res, lineDump);
        if (expandedLineVtyFormat) {
            res = modifyInputExpandLineVty(worker, res);
        }


        //
        // Done
        //
        traceInfo(worker, "Transforming input config done "+tickToString(start0));
        reportProgressStop(progress);
        return res;
    }


    /**
     * Expand abbrevitated interface name
     * @param
     * @return Full interface name
     */
    private String expandInterfaceName(String abbrevName) {
        String[][] ifNameMap = {
            { "Gi", "GigabitEthernet" },
            { "Fa", "FastEthernet" },
            { "Et", "Ethernet" },
            { "Te", "TenGigabitEthernet" },
            { "Po", "Port-Channel" },
            { "Vl", "Vlan" }
        };
        for (int i = 0; i < ifNameMap.length; i++) {
            if (abbrevName.equals(ifNameMap[i][0])) {
                return ifNameMap[i][1];
            }
        }
        return abbrevName;
    }


    /**
     * Quote description string
     * @param
     * @return Quoted description
     */
    private String quoteDescription(String toptag, String line) {

        // Ignore quoting the following service-insertion descriptions
        if (toptag.startsWith("service-insertion ") || toptag.startsWith("ap ")) {
            return line;
        }
        if (toptag.startsWith("wireless ") || toptag.startsWith("wlan ")) {
            return line;
        }

        int i = line.indexOf(" description ");

        // Special case for: ip msdp description <hostname> <description>
        int offset = 13;
        if (line.trim().startsWith("ip msdp description ")) {
            int space = line.indexOf(' ', i + offset);
            if (space > 0) {
                offset = space - i + 1;
            }
        }

        // Quote description string
        String desc = stringQuote(line.substring(i+offset).trim());
        return line.substring(0,i+offset) + desc;
    }


    /**
     * Split line into lines
     * @param start = number of words in the start
     * @param numPost = number of words in the end
     * @return
     */
    private boolean splitLines(StringBuilder sb, String spaces, String[] tokens, int numPre, int numPost) {
        if (tokens.length - numPre -numPost <= 1) {
            return false;
        }

        // Build prefix
        StringBuilder prefix = new StringBuilder(tokens[0]);
        for (int n = 1; n < numPre; n++) {
            prefix.append(" " + tokens[n]);
        }

        // Build postfix
        final int end = tokens.length - numPost;
        StringBuilder postfix = new StringBuilder();
        for (int n = end; n < tokens.length; n++) {
            postfix.append(" " + tokens[n]);
        }

        // Append lines
        for (int n = numPre; n < end; n++) {
            sb.append(spaces + prefix.toString() + " " + tokens[n] + postfix.toString() + "\n");
        }

        return true;
    }


    /**
     * Split line ending with range-list value into multiple lines
     * @return true if split
     */
    private boolean splitRangeList(StringBuilder sb, String line) {
        Pattern p = Pattern.compile("(.+) ([0-9]+[-,][0-9-,]+)");
        Matcher m = p.matcher(line);
        if (!m.find()) {
            sb.append(line+"\n");
            return false;
        }

        final String prefix = m.group(1);
        final String[] ranges = m.group(2).split(",");
        for (int r = 0; r < ranges.length; r++) {
            p = Pattern.compile("(\\d+)(?:[-](\\d+))?");
            m = p.matcher(ranges[r]);
            if (!m.find()) {
                continue;
            }
            int start = Integer.parseInt(m.group(1));
            int end = m.group(2) != null ? Integer.parseInt(m.group(2)) : start;
            for (int v = start; v <= end; v++) {
                sb.append(prefix + " " + v + "\n");
            }
        }
        return true;
    }


    /**
     * Split line ranges into multiple single lines with config, e.g. line 0/2/15 0/3/0
     * NOTE: CALLS 'show line' on device
     * @param
     * @return
     * @throws Exception
     */
    private String modifyInputExpandLine(NedWorker worker, String res, String lineDump) throws Exception {

        //
        // line x y
        //
        Pattern p = Pattern.compile("\n(line (\\d+) (\\d+))\r(.*?)?(?=\nline |\n!)", Pattern.DOTALL);
        Matcher m = p.matcher(res);
        StringBuffer sb = new StringBuffer();
        boolean logonce = true;
        String match;
        while (m.find()) {
            if (logonce) {
                traceVerbose(worker, "splitting 'line x y' config");
                logonce = false;
            }
            int start = Integer.parseInt(m.group(2));
            int end = Integer.parseInt(m.group(3));
            String config = "";
            if (m.groupCount() == 4) {
                config = m.group(4);
            }
            int num = 0;
            String buf = "";
            for (int port = start; port <= end; port++, num++) {
                buf += "\nline "+port+"\r"+config;
            }

            if (num > 0) {
                traceVerbose(worker, "transformed <= split '"+m.group(1)+"' into "+num+" lines");
                m.appendReplacement(sb, buf);
            } else {
                traceInfo(worker, "ERROR: failed to split up terminal line "+stringQuote(m.group(0)));
                m.appendReplacement(sb, m.group(0));
            }
        }
        m.appendTail(sb);
        if (!logonce) {
            res = sb.toString();
            logonce = true;
        }

        //
        // line X/a X/b
        //
        p = Pattern.compile("\n(line (\\d+)/(\\d+) \\2/(\\d+))\r(.*?)?(?=\nline |\n!)", Pattern.DOTALL);
        m = p.matcher(res);
        sb = new StringBuffer();
        while (m.find()) {
            if (logonce) {
                traceVerbose(worker, "splitting 'line X/a X/b' config");
                logonce = false;
            }
            int start = Integer.parseInt(m.group(3));
            int end = Integer.parseInt(m.group(4));
            String config = "";
            if (m.groupCount() == 5) {
                config = m.group(5);
            }
            int num = 0;
            String buf = "";
            for (int port = start; port <= end; port++, num++) {
                buf += "\nline "+m.group(2)+"/"+port+"\r"+config;
            }

            if (num > 0) {
                traceVerbose(worker, "transformed <= split '"+m.group(1)+"' into "+num+" lines");
                m.appendReplacement(sb, buf);
            } else {
                traceInfo(worker, "ERROR: failed to split up terminal line "+stringQuote(m.group(0)));
                m.appendReplacement(sb, m.group(0));
            }
        }
        m.appendTail(sb);
        if (!logonce) {
            res = sb.toString();
            logonce = true;
        }


        //
        // line X/a/m X/b/n
        //
        p = Pattern.compile(LINE_RANGE3_REGEX, Pattern.DOTALL);
        m = p.matcher(res);
        sb = new StringBuffer();
        while (m.find()) {
            if (logonce) {
                traceVerbose(worker, "splitting 'line X/a/m X/b/n' config");
                logonce = false;
            }
            String slot = m.group(2); // x
            int subslotStart = Integer.parseInt(m.group(3)); // y
            int subslotEnd = Integer.parseInt(m.group(5)); // a
            int portStart = Integer.parseInt(m.group(4)); // z
            int portEnd = Integer.parseInt(m.group(6)); // b
            String config = "";
            if (m.groupCount() == 7) {
                config = m.group(7);
            }
            String buf = "";
            int num = 0;
            if (subslotStart == subslotEnd) {
                // Single subslot, portStart and portEnd on same line
                for (int port = portStart; port <= portEnd; port++, num++) {
                    buf += "\nline "+slot+"/"+subslotStart+"/"+port+"\r"+config;
                }
            }

            else {
                // Range of multiple subslots, need to look up min & max lines
                final String[] lines = lineDump.split("\n");
                for (int s = subslotStart; s <= subslotEnd; s++) {

                    // Use 'show line' to derive start and end
                    int start = -1;
                    int end = 0;
                    final String root = slot+"/"+s+"/";
                    if (lines.length > 1) {
                        for (int l = 0; l < lines.length; l++) {
                            if (!lines[l].trim().startsWith(root)) {
                                continue;
                            }
                            if ((match = getMatch(lines[l], root+"(\\d+) ")) != null) {
                                int val = Integer.parseInt(match);
                                if (start == -1) {
                                    start = val; // pick first port as start
                                }
                                if (val > end) {
                                    end = val; // increase end
                                }
                            }
                        }

                        // No entries found in 'show line'
                        if (start == -1) {
                            traceInfo(worker, "ERROR: ignoring "+root+" in "+m.group(1)
                                      +" due to missing 'show line' entries");
                            continue;
                        }

                        // Start and end subslot exceptions
                        if (s == subslotStart) {
                            start = Math.max(start, portStart);
                        }
                        if (s == subslotEnd) {
                            end = Math.min(end, portEnd);
                        }
                    }

                    // No 'show line' input, simulate start and end
                    else {
                        start = portStart;
                        end = portEnd;
                        if (end < start) {
                            start = portEnd;
                            end = portStart;
                        }
                    }

                    // Create single line(s)
                    traceDev(worker, m.group(1)+" line "+root+" start = "+start+" end = "+end);
                    for (int port = start; port <= end; port++, num++) {
                        buf += "\nline "+root+port+"\r"+config;
                    }
                }
            }
            if (num > 0) {
                traceVerbose(worker, "transformed <= split '"+m.group(1)+"' into "+num+" lines");
                m.appendReplacement(sb, buf);
            } else {
                traceInfo(worker, "ERROR: failed to split up terminal line "+stringQuote(m.group(0)));
                m.appendReplacement(sb, m.group(0));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }


    /**
     * Split line vty ranges into multiple single lines with config, e.g. line vty 2 7
     * @param
     * @return
     */
    private String modifyInputExpandLineVty(NedWorker worker, String res) {
        Pattern p = Pattern.compile("\n(line vty (\\d+)[ ]+(\\d+))\r(.*?)?(?=\nline |\n!)", Pattern.DOTALL);
        Matcher m = p.matcher(res);
        StringBuffer sb = new StringBuffer();
        boolean logonce = true;
        while (m.find()) {
            if (logonce) {
                traceVerbose(worker, "splitting line vty range config");
                logonce = false;
            }
            int start = Integer.parseInt(m.group(2));
            int end = Integer.parseInt(m.group(3));
            String config = "";
            if (m.groupCount() == 4) {
                config = m.group(4);
            }

            // Range of vty lines, create single lines
            String buf = "";
            for (int port = start; port <= end; port++) {
                buf += "\nline vty "+port+"\r"+config;
            }

            if (end - start > 0) {
                traceVerbose(worker, "transformed <= split '"+m.group(1)+"' into "+(1+end-start)+" vty lines");
                m.appendReplacement(sb, buf);
            } else {
                traceInfo(worker, "WARNING: failed to split up vty line "+stringQuote(m.group(0)));
                m.appendReplacement(sb, m.group(0));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }


    /**
     * Inject read ned-settings in input config
     * @param
     * @return
     * @throws Exception
     */
    private String injectInput(NedWorker worker, String res) throws Exception {
        final long start = tick(0);

        traceVerbose(worker, "injecting input config...");

        StringBuilder first = new StringBuilder();
        StringBuilder last = new StringBuilder();

        //
        // (1) read/replace-config ned-setting - inject/replace in running-config
        //
        res = injectReplaceConfigNedSetting(worker, res, false);

        //
        // (2) read/inject-config ned-setting - inject config in running-config
        //
        if (!injectConfig.isEmpty()) {
            traceVerbose(worker, "applying read/inject-config ned-setting");
            for (int n = injectConfig.size()-1; n >= 0; n--) {
                String[] entry = injectConfig.get(n);
                if (entry[3] == null) {
                    entry[3] = entry[1] != null ? "after-each" : "first";
                }
                res = injectData(worker, res, entry, "<=");
            }
        }

        //
        // (3) read/inject-interface-config ned-setting - inject interface config first in matching interface(s)
        //
        if (!interfaceConfig.isEmpty()) {
            traceVerbose(worker, "applying read/inject-interface-config ned-setting");
            Pattern p = Pattern.compile("\ninterface (\\S+)(?: \\S+)?");
            Matcher m = p.matcher(res);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String ifname = m.group(1);
                String inject = "";
                for (int n = 0; n < interfaceConfig.size(); n++) {
                    String[] entry = interfaceConfig.get(n);
                    if (findString(entry[0], ifname) >= 0) {
                        inject += ("\r\n " + entry[1]);
                    }
                }
                if (!inject.isEmpty()) {
                    traceInfo(worker, "transformed <= injected: "+stringQuote(inject)+" first in interface "+ifname);
                }
                m.appendReplacement(sb, m.group(0) + inject);
            }
            m.appendTail(sb);
            res = sb.toString();
        }

        //
        // Done
        //
        traceVerbose(worker, "injecting input config done "+tickToString(start));
        return first.toString() + res + last.toString();
    }


    /**
     * Apply read/replace-config ned-setting
     * @param
     */
    private String injectReplaceConfigNedSetting(NedWorker worker, String res, boolean transId) {

        if (replaceConfig.isEmpty()) {
            return res;
        }

        traceVerbose(worker, "applying read/replace-config ned-setting");

        for (int n = 0; n < replaceConfig.size(); n++) {
            String[] entry = replaceConfig.get(n);
            boolean isTransIdOnly = entry[3] != null && "trans-id-only".equals(entry[3]);
            if (transId && !isTransIdOnly) {
                continue;
            }
            if (!transId && isTransIdOnly) {
                continue;
            }
            final String regexp = entry[1] + "(?:[\r])?";
            final String replacement = entry[2];
            traceDev(worker, "   "+entry[0]+" read/replace-config regex = "+stringQuote(regexp));
            try {
                Pattern p = Pattern.compile(regexp, Pattern.DOTALL);
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

        return res;
    }


    /**
     * Inject config in CDB not shown on device in config dump to avoid diff
     * @param
     */
    private void injectCachedExec(NedWorker worker, StringBuilder first, int th) {

        //
        // key config-key password-encrypt
        //
        ConfValue val;
        try {
            val = maapi.safeGetElem(th, confRoot + "key/config-key/password-encrypt");
            if (val != null) {
                String password = val.toString();
                traceInfo(worker, "SECRETS - transformed <= injected 'key config-key password-encrypt "+password+"'");
                first.append("key config-key password-encrypt " + password + "\n");
            }
        } catch (Exception ignore) {
            // Ignore Exception
        }

        //
        //  cts credentials id * password
        //
        try {
            val = maapi.safeGetElem(th, new ConfPath(confRoot + "cts/credentials/id"));
            if (val != null) {
                final String id = val.toString();
                val = maapi.safeGetElem(th, new ConfPath(confRoot + "cts/credentials/password"));
                if (val != null) {
                    String password = val.toString();
                    traceInfo(worker, "transformed <= injected 'cts credentials id "+id+" password <HIDDEN>'");
                    first.append("cts credentials id "+id+" password "+password + "\n");
                }
            }
        } catch (Exception ignore) {
            // Ignore Exception
        }
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
     * Retrieve partial running config from device
     * @param
     * @throws Exception
     */
    @Override
    public void showPartial(NedWorker worker, String[] cmdpaths) throws Exception {
        final long start = tick(0);
        traceInfo(worker, "BEGIN SHOW-PARTIAL String[]");
        showPartialInternal(schema, maapi, turboParserEnable, worker, cmdpaths);
        traceInfo(worker, "DONE SHOW-PARTIAL "+tickToString(start));
    }


    /**
     * Retrieve partial running config from device
     * @param
     * @throws Exception
     */
    @Override
    public void showPartial(NedWorker worker, ConfPath[] paths) throws Exception {
        final long start = tick(0);
        traceInfo(worker, "BEGIN SHOW-PARTIAL ConfPath[]");
        showPartialInternal(schema, maapi, turboParserEnable, worker, paths);
        traceInfo(worker, "DONE SHOW-PARTIAL "+tickToString(start));
    }


    /**
     * Get device configuration and perform input transformations
     * @param
     * @return
     * @throws Exception
     */
    @Override
    protected String getDeviceConfiguration(NedWorker worker) throws Exception {
        final String dump = getConfig(worker);
        String res = modifyInput(worker, dump);
        return getConfigOnly(worker, res);
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
    @Override
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

        try {
            // (1) NETSIM, optionally use confd-state transaction id
            String res = null;
            String which = "GET";
            if (isNetsim() && "confd-state-trans-id".equals(transIdMethod)) {
                String show = print_line_exec(worker, "show confd-state internal cdb datastore running transaction-id");
                if (show.contains("error")) {
                    throw new NedException("Failed to run get confd running transaction-id");
                }
                this.lastTransactionId = show.substring(show.indexOf(' ')+1).trim();
            }

            // (2) Use last cached transaction-id from show()
            else if (transIdMethod.startsWith("config-hash") && this.lastTransactionId != null) {
                traceInfo(worker, "CACHED TRANS_ID = "+this.lastTransactionId);
            }

            // (3) Use last cached transformed config from applyConfig() secret code
            else if (transIdMethod.startsWith("config-hash") && this.lastTransformedConfig != null) {
                res = this.lastTransformedConfig;
                which = "APPLY";
            }

            // (4) Use 'Last configuration change' string from running-config
            else if ("last-config-change".equals(transIdMethod) && isDevice()) {
                res = print_line_exec(worker, "show running-config | include Last configuration change");
                if (!res.contains("Last configuration change")) {
                    throw new NedException("Failed to get running-config 'Last configuration change' string");
                }
                res = res + res + res + res;
                which = "LAST-CFG";
            }

            // (5) Use 'show configuration id' command
            else if ("config-id".equals(transIdMethod) && isDevice()) {
                res = print_line_exec(worker, "show configuration id");
                if (isExecError(res)) {
                    throw new NedException("Failed to use 'show configuration id' for transaction id");
                }
                res = res + res + res + res;
                which = "CFG-ID";
            }

            // (6) Use 'show configuration history' command
            else if ("config-history".equals(transIdMethod) && isDevice()) {
                res = print_line_exec(worker, "show configuration history");
                if (isExecError(res)) {
                    throw new NedException("Failed to use 'show configuration history' for transaction id");
                }
                res = res + res + res + res;
                which = "CFG-HIS";
            }

            // (7) Get config from device and transform it
            else {
                res = modifyInput(worker, getConfig(worker));
            }

            // Calculate transaction id
            if (res != null) {
                this.lastTransactionId = calculateTransId(worker, res);
            }

            // Done
            traceInfo(worker, which+" TRANS_ID = "+this.lastTransactionId);
            traceInfo(worker, "DONE GET-TRANS-ID ("+this.lastTransactionId+") "+tickToString(start));
            worker.getTransIdResponse(this.lastTransactionId);

        } finally {
            // Clear cached data
            clearDataCache();
        }
    }


    /**
     * Calculate transaction-id
     * @param
     * @return
     * @throws Exception
     */
    private String calculateTransId(NedWorker worker, String res) throws Exception {

        // Trim config of dynamic info and empty lines
        StringBuilder sb = new StringBuilder();
        String[] lines = res.trim().split("\n");
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("Load for ")) {
                continue;
            }
            if (trimmed.startsWith("Time source is NTP")) {
                continue;
            }
            if (trimmed.startsWith("No time source")) {
                continue;
            }
            sb.append(lines[i]+"\n");
        }
        res = sb.toString();

        // ned-settings cisco-ios read replace-config * trans-id-only
        res = injectReplaceConfigNedSetting(worker, res, true);

        // ned-settings cisco-ios read transaction-id-method config-hash-modeled
        if ("config-hash-modeled".equals(transIdMethod)) {
            traceInfo(worker, "Stripping unmodeled config from hash transaction id calculation");
            res = filterConfig(res, schema, maapi, worker, null, false).toString();
        }

        // Sort certain config since some IOS devices reorder entries after reboot
        res = checksumSortConfig(worker, res);

        traceVerbose(worker, "TRANS-ID-BUF=\n+++ begin\n"+res+"\n+++ end");

        // Calculate checksum of running-config
        byte[] bytes = res.getBytes("UTF-8");
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] thedigest = md.digest(bytes);
        BigInteger md5Number = new BigInteger(1, thedigest);
        return md5Number.toString(16);
    }


    /**
     *
     * @param
     * @return
     */
    private String checksumSortConfig(NedWorker worker, String res) {

        //
        // Sort lines:
        //

        // top mode lines
        res = sortLines(worker, res, "ip route vrf ");
        res = sortLines(worker, res, "ipv6 route "); // including "ipv6 route vrf "
        res = sortLines(worker, res, "ip nat translation max-entries vrf ");

        // router bgp * /
        res = sortLines(worker, res, "aggregate-address ");
        res = sortLines(worker, res, "neighbor ");

        // route-map * / match policy-list *
        res = sortLines(worker, res, "match policy-list ");

        //
        // Sort words in lines:
        //
        String toptag = "";
        StringBuilder sb = new StringBuilder();
        String[] lines = res.split("\n");
        for (int n = 0; n < lines.length; n++) {
            String trimmed = lines[n].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (isTopExit(lines[n])) {
                toptag = "";
            } else if (Character.isLetter(lines[n].charAt(0))) {
                toptag = lines[n].trim();
            }

            // route-map * / match interface *
            if (toptag.startsWith("route-map")
                && trimmed.startsWith("match interface ")) {
                String sortedline = sortWords(worker, trimmed, 2);
                sb.append(sortedline+"\n");
            }

            // Default, do not reorder
            else {
                sb.append(lines[n]+"\n");
            }
        }
        res = sb.toString();

        return res.trim();
    }


    /**
     *
     * @param
     * @return
     */
    private String sortLines(NedWorker worker, String res, String sortline) {

        // Sort subsequent lines
        int numSorted = 0;
        StringBuilder sb = new StringBuilder();
        String[] lines = res.split("\n");
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if (line.trim().isEmpty()) {
                continue;
            }
            if (!line.trim().startsWith(sortline)) {
                sb.append(line+"\n");
                continue;
            }

            // First matching line, assemble all subsequent matching lines
            ArrayList<String> arraylist = new ArrayList<>();
            arraylist.add(line);
            for (int s = n + 1; s < lines.length; s++) {
                if (!lines[s].trim().startsWith(sortline)) {
                    break;
                }
                arraylist.add(lines[s]);
                lines[s] = "";
            }

            // Only one line, continue
            if (arraylist.size() == 1) {
                sb.append(line+"\n");
                continue;
            }

            // Sort lines and add back in place sorted
            numSorted += arraylist.size();
            String[] sortlines = arraylist.toArray(new String[arraylist.size()]);
            Arrays.sort(sortlines);
            sb.append("! sort begin\n");
            for (int s = 0; s < sortlines.length; s++) {
                sb.append(sortlines[s]+"\n");
            }
            sb.append("! sort end\n");
        }
        if (numSorted < 1) {
            return res;
        }

        traceInfo(worker, "transformed <= sorted "+numSorted+" '"+sortline+"' lines for hash checksum");
        return sb.toString();
    }


    /**
     *
     * @param
     * @return
     */
    private String sortWords(NedWorker worker, String trimmed, int start) {
        String[] lines = trimmed.split(" +");
        Arrays.sort(lines, start, lines.length);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(" "+lines[i]);
        }
        String sortedline = sb.toString();

        if (!sortedline.equals(trimmed)) {
            traceInfo(worker, "transformed <= sorted '"+trimmed+"' for hash checksum");
        }
        return sortedline;
    }


    /*
     **************************************************************************
     * prepareDry
     **************************************************************************
     */

    /**
     * Display config for commit dry-run outformat native
     * @param
     * @throws Exception
     */
    @Override
    public void prepareDry(NedWorker worker, String data) throws Exception {
        String originalIosModel = this.iosmodel;
        if (trace && session != null) {
            session.setTracer(worker);
        }
        final int nsoRunningVersion = getNsoRunningVersion();
        if (nsoRunningVersion < 0x7000000 && nsoRunningVersion != 0x6060400) {
            traceInfo(worker, "\n"+data);
        }

        // ShowRaw used in debugging, to see cli commands before modification
        final long start = tick(0);
        if (showRaw || data.contains("tailfned raw-run\n")) {
            traceInfo(worker, "BEGIN PREPARE-DRY raw");
            showRaw = false;
            traceInfo(worker, "DONE PREPARE-DRY "+tickToString(start));
            worker.prepareDryResponse(data);
            return;
        }

        // Init String builder
        StringBuilder sb = new StringBuilder();
        if (devPrepareDryModel != null) {
            // cisco-ios developer prepare-dry-model
            if (logVerbose) {
                sb.append("! Generated for "+devPrepareDryModel+" model\n");
            }
            this.iosmodel = devPrepareDryModel;
        }

        // Log and trace before changes
        String log = "BEGIN PREPARE-DRY model="+iosmodel+" version="+iosversion;
        if (session == null) {
            log += " offline";
        }
        traceInfo(worker, log);

        // Modify output
        try {
            data = modifyOutput(worker, data, "PREPARE-DRY");

            // Rebuild data and trim tailfned if not log-verbose
            String[] lines = data.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (logVerbose && line.startsWith("tailfned ")) {
                    sb.append(line+"\n");
                } else {
                    // Modify texts
                    line = modifyOutputTexts(worker, line);
                    sb.append(line+"\n");
                }
            }
            data = sb.toString().trim()+"\n";
        } finally {
            this.iosmodel = originalIosModel;
        }

        traceInfo(worker, "DONE PREPARE-DRY "+tickToString(start));
        worker.prepareDryResponse(data);
    }


    /*
     **************************************************************************
     * applyConfig
     **************************************************************************
     *
     * NSO PHASES:
     *          prepare (send data to device)
     *           /                              \
     *          v     v
     *       abort | commit (send confirmed commit)
     *               /                          \
     *              v     v
     *          revert | persist (send confirming commit)
     */

    /**
     * Apply config
     * @param
     * @throws NedException, IOException, SSHSessionException, ApplyException
     */
    @Override
    public void applyConfig(NedWorker worker, int cmd, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {
        final long start = tick(0);
        if (trace) {
            session.setTracer(worker);
        }
        traceInfo(worker, "BEGIN APPLY-CONFIG");

        // Apply the commit
        doApplyConfig(worker, cmd, data);

        // Debug code:
        if (failphase.contains("apply")) {
            traceInfo(worker, "DEBUG: Simulating failed applyConfig");
            failphase = failphase.replace("apply", "");
            doApplyConfig(worker, cmd, "\nhostname applydiff\n");
            throw new NedException("DEBUG :: Exception in applyConfig");
        }

        traceInfo(worker, "DONE APPLY-CONFIG "+tickToString(start));
    }


    /**
     * Apply config doer
     * Called from: applyConfig, abort and revert
     * @param
     * @throws NedException, IOException, SSHSessionException, ApplyException
     */
    private void doApplyConfig(NedWorker worker, int cmd, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {

        // Empty data
        if (data.trim().isEmpty()) {
            traceInfo(worker, "Empty data -> skip next writeMemory");
            ignoreNextWrite = true;
            return;
        }

        // Clear cached data
        clearDataCache();

        // Save for archive %i filename transaction id info
        lastToHandle = worker.getToTransactionId();

        // Modify data
        if (!data.contains("tailfned raw-run\n")) {
            data = modifyOutput(worker, data, "APPLY-CONFIG");
        }

        // Config trimmed to empty
        if (data.trim().isEmpty()) {
            traceInfo(worker, "Empty commit -> skip next write to memory");
            ignoreNextWrite = true;
            return;
        }

        //
        // Send data
        //
        String[] lines = data.split("\n");
        traceInfo(worker, "BEGIN SENDING "+lines.length+" line(s):\n"+data);
        final long start = tick(0);
        NedProgress.Progress progress = reportProgressStart(this, NedProgress.SEND_CONFIG);
        int fromTh = worker.getFromTransactionId();
        int toTh = worker.getToTransactionId();
        try {
            // Attach to CDB
            maapiAttach(worker, fromTh, toTh);

            // Reconnect to device if remote end closed connection due to being idle
            if (session.serverSideClosed() || "reconnect".equals(failphase)) {
                failphase = "";
                reconnectDevice(worker);
            }

            // Enter config mode
            enterConfig(worker, data);

            // NETSIM - ned-settings cisco-ios write transfer-via-file
            if (isNetsim() && writeTransferViaFile && isLocalIp(this.ip)) {
                transferViaFile(worker, cmd, data);
            }

            // REAL DEVICE or remote NETSIM
            else {
                sendConfig(worker, cmd, lines);
            }

            // Exit config mode
            exitConfig(worker, "do-apply");

            // All commands accepted by device, cache secrets and defaults
            try {
                if (secrets.needUpdate()) {
                    final String dump = getConfig(worker);
                    lastTransformedConfig = modifyInput(worker, dump);
                    secrets.cache(worker, lastTransformedConfig);
                }
                if (isDevice()) {
                    defaults.cache(worker, this.extSentAction);
                    handleAllTrapsOutput(worker);
                }
            } catch (Exception e) {
                throw new NedException(e.getMessage(), e);
            }

            // Done
            traceInfo(worker, "DONE SENDING "+lines.length+" line(s) "+tickToString(start));
            reportProgressStop(progress);

        } catch (Exception e) {
            reportProgressStop(progress, NedProgress.ERROR);
            throw e;

        } finally {
            maapiDetach(worker, fromTh, toTh);
        }
    }


    /**
     * Clear cached data
     * @param
     * @return True if local ip address, else false
     */
    private void clearDataCache() {
        this.lastPrompt = "";
        this.lastTransactionId = null;
        this.lastTransformedConfig = null;
        this.ignoreNextWrite = false;
        this.snmpAllTraps = 0;
        this.useRevert = false;
        this.oldConfigKey = null;
        this.sentConfigKey = false;
    }


    /**
     * Called if '[no] snmp-server enable traps' was successfully sent to device
     * @param
     * @throws Exception
     */
    private void handleAllTrapsOutput(NedWorker worker) throws Exception {
        if (apiSnmpServerEnableAllTraps == 0 || this.snmpAllTraps == 0) {
            return;
        }

        // create
        int length = 0;
        if (this.snmpAllTraps == 1) {
            traceVerbose(worker, "all-traps: created, counting traps");
            setReadTimeout(worker);
            String res = print_line_exec(worker, "show run | i snmp-server enable traps ");
            if (!res.trim().isEmpty()) {
                length = res.trim().split("\n").length;
            }
            traceInfo(worker, "all-traps: created "+length+" traps");
        }

        // delete
        else if (this.snmpAllTraps == -1) {
            traceInfo(worker, "all-traps: deleted all traps");
        }

        final String path = this.operRoot + "/snmp-server-enable-num-traps";
        this.cdbOper.setElem(new ConfBuf(String.format("%d", length)), path);
    }


    /**
     * Called in show() to determine if 'snmp-server enable all-traps' should be injected
     * @param
     * @throws Exception
     */
    private void handleAllTrapsInput(NedWorker worker, int numTrapsDev, StringBuilder sbin) throws Exception {
        if (apiSnmpServerEnableAllTraps == 0 || numTrapsDev == 0) {
            return;
        }

        traceInfo(worker, "all-traps: found "+numTrapsDev+" 'snmp-server enable traps' entries");
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
            traceInfo(worker, "all-traps: transformed <= injected 'snmp-server enable all-traps'");
            sbin.append("snmp-server enable all-traps\n");
        }
    }


    /**
     * Check if local ip address
     * @param
     * @return True if local ip address, else false
     */
    private boolean isLocalIp(InetAddress addr) {
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()) {
            return true;
        }
        try {
            return NetworkInterface.getByInetAddress(addr) != null;
        } catch (Exception e) {
            return false;
        }
    }


    /**
     * NETSIM optimization - transfer config via file
     * @param
     * @throws ApplyException
     */
    private void transferViaFile(NedWorker worker, int cmd, String data) throws ApplyException {

        setWriteTimeout(worker);

        // Write config to /tmp temporary file
        String tmpfile = "/tmp/"+device_id+"-apply-config.txt";
        traceInfo(worker, "Writing config to " + tmpfile);
        if (!writeFile(data, tmpfile)) {
            throw new ApplyException(tmpfile, "failed to write config to /tmp", true, true);
        }

        // Load config from /tmp temporary file
        traceInfo(worker, "Loading config from " + tmpfile);
        try {
            String res = print_line_exec(worker, "load merge " + tmpfile);
            if (res.contains("Error:")) {
                if (writeIgnoreAbortErrors && cmd == NedCmd.ABORT_CLI) {
                    traceInfo(worker, "ignoring ABORT load merge "+tmpfile+" error: "+stringQuote(res));
                } else {
                    throw new NedException(stringQuote(res));
                }
            }
        } catch (Exception e) {
            throw new ApplyException(e.getMessage(), " load merge "+tmpfile+" ERROR", true, true);
        }
    }


    /**
     * Restore key config-key value
     * @param
     */
    private void restoreConfigKey(NedWorker worker, int cmd, String[] lines) {
        if (cmd == NedCmd.ABORT_CLI || cmd == NedCmd.REVERT_CLI) {
            return;
        }
        traceInfo(worker, "SECRET - restoring 'key config-key password-encrypt'");
        String newKey;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i] != null
                && lines[i].startsWith("key config-key password-encrypt ")
                && (newKey = getMatch(lines[i], "password-encrypt (\\S+)")) != null) {
                try {
                    final String oldKey = this.oldConfigKey;
                    this.oldConfigKey = mCrypto.decrypt(newKey);
                    print_line_wait(worker, cmd, "key config-key password-encrypt "+oldKey, 0, "", 0);
                } catch (Exception e) {
                    traceInfo(worker, "Ignored restoreConfigKey() Exception: "+e.getMessage());
                }
                break;
            }
        }
    }


    /**
     * Modify output texts for real devices
     * @param
     * @return
     */
    private String modifyOutputTexts(NedWorker worker, String line) {

        if (isNetsim()) {
            return line;
        }

        // banner motd|exec|incoming|login|prompt-timeout|etc.
        String match;
        String trimmed = line.trim(); // WARNING: Line is already trimmed in PREPARE
        if (line.startsWith("banner ")) {
            Pattern p = Pattern.compile("banner (\\S+)[ ]+(.*)");
            Matcher m = p.matcher(line);
            if (m.find()) {
                String message = textDequote(m.group(2));
                message = message.replace("\r", "");  // device adds \r itself
                message = message.replace("\t", " ");  // can't include TAB
                traceVerbose(worker, "transformed => dequoted banner "+m.group(1));
                line = "banner "+m.group(1)+" ^"+message+"^";
                waitForEcho = Echo.TEXT;
            }
        }

        // aaa authentication banner
        else if ((match = getMatch(line, "aaa authentication banner (.+)")) != null) {
            String message = stringDequote(match);
            message = message.replace("\r", "");  // device adds \r itself
            line = "aaa authentication banner " + "^" + message + "^";
            waitForEcho = Echo.TEXT;
        }

        // aaa authentication fail-message
        else if ((match = getMatch(line, "aaa authentication fail-message (.+)")) != null) {
            String message = stringDequote(match);
            message = message.replace("\r", "");  // device adds \r itself
            line = "aaa authentication fail-message " + "^" + message + "^";
            waitForEcho = Echo.TEXT;
        }

        // menu <name> title ^C <title text> \n^C
        else if (line.matches("^\\s*menu \\S+ title .*$")) {
            int i = line.indexOf("title ");
            String title = stringDequote(line.substring(i+6).trim());
            title = title.replace("\r", "");  // device adds \r itself
            line = line.substring(0,i+6) + "^" + title + "^";
            waitForEcho = Echo.TEXT;
        }

        // menu <name> prompt ^C <prompt text> \n^C
        else if (line.matches("^\\s*menu \\S+ prompt .*$")) {
            int i = line.indexOf("prompt ");
            String prompt = stringDequote(line.substring(i+7).trim());
            prompt = prompt.replace("\r", "");  // device adds \r itself
            line = line.substring(0,i+7) + "^" + prompt + "^";
            waitForEcho = Echo.TEXT;
        }

        // macro name <name> "command1\r\ncommand2\r\ncommandN\r\n"
        else if (line.matches("^\\s*macro name .*$")) {
            int i = line.indexOf("macro name ");
            i = line.indexOf(' ',i+11);
            String commands = stringDequote(line.substring(i+1).trim());
            commands = commands.replace("\r", "");  // device adds \r itself
            line = line.substring(0,i+1) + "\n" + commands + "@";
            waitForEcho = Echo.TEXT;
        }

        // line * / refuse-message
        // line * / vacant-message
        else if (trimmed.startsWith("refuse-message ") || trimmed.startsWith("vacant-message ")) {
            Pattern p = Pattern.compile("((?:refuse|vacant)-message )(.+)");
            Matcher m = p.matcher(line);
            if (m.find()) {
                String message = stringDequote(m.group(2));
                message = message.replace("\r", "");  // device adds \r itself
                line = " " + m.group(1) + "^" + message + "^";
                waitForEcho = Echo.TEXT;
            }
        }

        return line;
    }


    /**
     *
     * @param
     * @return
     * @throws NedException
     */
    private String modifyOutputLineNetsim(String data) throws NedException {

        String[] lines = data.split("\n");
        StringBuilder sbout = new StringBuilder();
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            String trimmed = lines[n].trim();
            String noutput = null;

            // description patch for netsim, quote text and escape "
            int i;
            if ((i = line.indexOf("description ")) >= 0 && !line.contains("ip msdp description ")) {
                String desc = line.substring(i+12).trim(); // Strip initial white spaces, added by NCS
                if (desc.charAt(0) != '"') {
                    desc = desc.replaceAll("\\\"", "\\\\\\\""); // Convert " to \"
                    noutput = line.substring(0,i+12) + "\"" + desc + "\""; // Quote string, add ""
                }
            }

            // voice translation-rule * / rule
            else if ("no".equals(rollBackOctal)
                     && getMatches(trimmed, "rule (\\d+) ((?:[\"])?[/].*?[/](?:[\"])?) "
                                   +"((?:[\"])?[/].*?[/](?:[\"])?)") != null) {
                noutput = line.replace("\\", "\\\\");
            }

            // Transform lines[n] -> XXX
            if (noutput != null && !noutput.equals(lines[n])) {
                sbout.append(noutput+"\n");
            } else if (lines[n] != null && !lines[n].isEmpty()) {
                sbout.append(lines[n]+"\n");
            }
        }

        return "\n" + sbout.toString();
    }


    /**
     * Modify line by line for output to real device
     * @param
     * @return
     * @throws NedException
     */
    private String modifyOutputLine(NedWorker worker, String data, int fromTh, int toTh) throws NedException {
        String[] group;
        String match;

        String[] lines = data.split("\n");
        StringBuilder sb = new StringBuilder();
        String toptag = "";
        modeStack = new ModeStack();
        for (int n = 0; n < lines.length; n++) {
            String output = null;
            String line = lines[n];
            String trimmed = lines[n].trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            final String cmdtrim = trimmed.startsWith("no ") ? trimmed.substring(3) : trimmed;
            final String nextline = (n + 1 < lines.length) ? lines[n+1] : "";

            // Update toptag
            modeStack.update(lines[n]);
            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = trimmed;
            }

            //
            // key config-key password-encrypt - delete key before change
            //
            if (line.startsWith("key config-key password-encrypt ")
                && (this.oldConfigKey = maapiGetLeafString(worker, fromTh,
                                                      confRoot + "key/config-key/password-encrypt", true)) == null) {
                traceInfo(worker, "transformed => injected 'no key config-key password-encrypt'");
                sb.append("no key config-key password-encrypt\n");
            }

            //
            // Ignore duplicate lines:
            //   no cos map *
            //   no qos map dscp policed
            //   no mls cos map X *
            // interface * / no switchport
            //
            if ((trimmed.startsWith("no qos map ")
                 || trimmed.startsWith("no qos map dscp policed")
                 || trimmed.startsWith("no mls qos map "))
                && line.equals(nextline)) {
                continue;
            }

            //
            // no controller ACR
            //
            if (line.startsWith("no interface CEM-ACR") || line.startsWith("no controller SONET-ACR")) {
                output = "!suppressed: "+line;
            }

            //
            // username algorithm-type & secret/type check
            //
            else if (!isDry && line.startsWith("username ") && line.contains(" algorithm-type ")
                     && (match = getMatch(line, "username (\\S+)")) != null
                     && maapiExists(worker, toTh, confRoot+"username{"+match+"}/secret/type")) {
                throw new NedException("Invalid input, secret type not allowed with algorithm-type in '"+line+"'");
            }

            //
            // logging host *
            //
            else if (!isDry && apiIosCommon && line.startsWith("logging host ")) {
                throw new NedException("'api ios-common' ned-setting enabled: Use 'logging *' list only");
            }

            //
            // no logging source-interface <interface>
            // logging source-interface <interface2>
            //
            else if ((match = getMatch(line, "^no logging source-interface (\\S+)$")) != null
                     && getMatch(nextline, "^logging source-interface (\\S+)$") != null) {
                continue;
            }

            //
            // object-group network * / <addr> <mask>
            //
            else if (!isDry &&
                     toptag.startsWith("object-group network ") && line.startsWith(" ")
                     && !isValidAddressAndMask(line)) {
                throw new NedException("'"+line+"' : Inconsistent address and mask"+modeStack.toString());
            }

            //
            // enable algorithm-type secret
            //
            else if (line.startsWith("enable secret ") || line.startsWith("no enable secret ")) {
                output = line.replaceFirst("secret (algorithm-type \\S+)", "$1 secret");
            }

            //
            // no ntp server * prefer
            //
            else if (line.startsWith("no ntp server ") && line.contains(" prefer")) {
                output = line.replace(" prefer", ""); // for 3845 12.4(15)T7 [CISCOIOS-2076]
            }

            //
            // no modemcap *
            //
            else if (line.startsWith("no modemcap ")
                     && (match = getMatch(line, "no modemcap entry ([A-Za-z0-9_-]+)[:]")) != null) {
                output = "no modemcap entry "+match;
            }

            //
            // class-map * / match vlan inner *
            // class-map * / match vlan *
            // class-map * / match input vlan *
            //
            else if (toptag.startsWith("class-map ")
                       && (cmdtrim.startsWith("match vlan ") || cmdtrim.startsWith("match input vlan "))) {
                output = line.replace(",", " ");
            }

            //
            // vlan * / name
            //
            else if (toptag.startsWith("vlan ") && trimmed.startsWith("name ")
                     && trimmed.substring(5).trim().contains(" ")
                     && (match = getMatch(trimmed, "name\\s+(\\S.+)")) != null) {
                output = line.replace(match, "\""+match+"\"");
            }

            //
            // cable profile service-group * / load-balance docsis-group * profile * / downstream sg-channel *
            // cable profile service-group * / mac-domain * / downstream sg-channel * [upstream "*"]
            //
            else if (toptag.startsWith("cable profile service-group ")
                     && cmdtrim.startsWith("downstream sg-channel ")) {
                if ((match = getMatch(line, " sg-channel (\\S+)")) != null) {
                    output = line.replace(" sg-channel "+match, " sg-channel "+match.replace(",", " "));
                    output = output.replace("\"", ""); // upstream .+
                }
            }

            //
            // redirect server-group * / server ip * port *
            //
            else if (toptag.startsWith("redirect server-group ")
                     && line.startsWith(" server ip") && line.endsWith(" port 0")) {
                output = line.replace(" port 0", "");
            }

            //
            // no cable service class * name <name>
            //
            else if (trimmed.startsWith("no cable service class ")
                     && (match = getMatch(trimmed, "^no cable service class (\\d+) name \\S+$")) != null) {
                output = "no cable service class "+match;
            }

            //
            // parameter-map type regexp * / pattern *
            //
            else if (toptag.startsWith("parameter-map type regex ")
                     && cmdtrim.startsWith("pattern ")
                     && (match = getMatch(trimmed, "pattern \\\"(.*)\\\"$")) != null) {
                output = " pattern " + match;
            }

            //
            // crypto pki profile enrollment * / authentication command
            //
            else if (toptag.startsWith("crypto pki profile enrollment ")
                     && cmdtrim.startsWith("authentication command ")
                     && (match = getMatch(trimmed, "authentication command\\s+(\\\".*\\\")")) != null) {
                output = " authentication command " + passwordDequote(match);
            }

            //
            // no crypto ikev2 authorization policy default
            //
            else if ("no crypto ikev2 authorization policy default".equals(trimmed)) {
                output = "default crypto ikev2 authorization policy";
            }

            //
            // no crypto ikev2 policy-default
            //
            else if ("crypto ikev2 policy-default-disabled".equals(line)) {
                output = "no crypto ikev2 policy default";
            } else if ("no crypto ikev2 policy-default-disabled".equals(line)) {
                output = "default crypto ikev2 policy";
            } else if ("no crypto ikev2 policy default".equals(line)) {
                if (data.contains("\ncrypto ikev2 policy-default-disabled\n")) {
                    continue;
                }
                sb.append(line+"\n");
                output = "default crypto ikev2 policy"; // remove no-entry on device
            }

            //
            // crypto map * ipsec-isakmp / set peer-default *
            //
            else if (toptag.startsWith("crypto map ") && line.startsWith(" set peer-default ")) {
                output = line.replace(" set peer-default ", " set peer ") + " default";
            }

            //
            // router ospf * / discard-route
            //
            else if (toptag.startsWith("router ospf ") && "discard-route external disabled".equals(trimmed)) {
                output = " no discard-route external\n";
            } else if (toptag.startsWith("router ospf ") && "discard-route internal disabled".equals(trimmed)) {
                output = " no discard-route internal\n";
            }

            //
            // router bgp * / neighbor * password 7
            //
            else if (autoBgpNbrPasswordPatch
                     && toptag.startsWith("router bgp ")
                     && line.startsWith(" neighbor ") && line.contains(" password 7 ")
                     && (match = getMatch(line, "( neighbor \\S+ password )")) != null) {
                // IOS bug patch: router bgp * / neighbor * password 7 [CISCOIOS-1418]
                traceInfo(worker, "transformed => pre-injected dummy password in "+match);
                sb.append(match + "0 bgp-nbr-password-patch\n");
            }

            //
            // cts credentials id
            //
            else if (trimmed.startsWith("cts credentials id ")) {
                output = "do "+trimmed;
            } else if (trimmed.startsWith("no cts credentials id ")) {
                output = "!"+trimmed;
            }

            //
            // chat-script * <script>
            //
            else if (toptag.contains("chat-script ")
                     && (match = getMatch(trimmed, "chat-script \\S+ (.*)")) != null) {
                String script = stringDequote(match);
                output = line.replace(match, script);
            }

            //
            // kron policy-list * / cli *
            //
            else if (toptag.startsWith("kron policy-list ")
                     && cmdtrim.startsWith("cli ")
                     && (match = getMatch(trimmed, "cli (.+)")) != null) {
                output = line.replace(match, stringDequote(match));
            }

            //
            // crypto pki trustpoint * / subject-name
            //
            else if (toptag.startsWith("crypto pki trustpoint ") && cmdtrim.startsWith("subject-name ")
                     && (match = getMatch(line, "subject-name\\s+(\\\".+\\\")")) != null) {
                output = line.replace(match, stringDequote(match));
            }

            //
            // alias <mode> <name> *
            //
            else if (cmdtrim.startsWith("alias ") &&
                     (group = getMatches(line, "((?:no )?alias \\S+ \\S+ )\\\"(.*)\\\"")) != null) {
                output = group[1] + passwordDequote(group[2]);
            }

            //
            // snmp-server location|contact *
            //
            else if (cmdtrim.startsWith("snmp-server ")
                     && (group = getMatches(line, "((?:no )?snmp-server (?:location|contact) )\\\"(.*)\\\"")) != null) {
                output = group[1] + passwordDequote(group[2]);
            }

            //
            // interface * / ip address
            // interface * / peer default ip address
            //
            else if (toptag.startsWith("interface ")
                     && ("ip address".equals(trimmed) || "peer default ip address".equals(trimmed))) {
                output = ""; // " !ip address";
            }

            // interface * / cable rf-channels channel-list x-y z bandwidth-percent
            // interface * / cable rf-channels controller ? channel-list x-y z bandwidth-percent
            //
            else if (toptag.startsWith("interface ")
                     && line.contains("cable rf-channels ") && line.contains(" channel-list ")) {
                output = line.replace(",", " ");
            }

            //
            // disable passive-interface
            //
            else if (line.contains("no disable passive-interface ")) {
                output = line.replace("no disable passive-interface ", "passive-interface ");
            }
            else if (line.contains("disable passive-interface ")) {
                output = line.replace("disable passive-interface ", "no passive-interface ");
            }

            //
            // no-list - generic trick for no-lists
            //
            else if (line.contains("no-list ")) {
                line = line.replace("no-list ", "");
                if (line.matches("^\\s*no .*$")) {
                    output = line.replace("no ", "");
                } else {
                    output = line.replace(line.trim(), "no " + line.trim());
                }
            }

            // diagnostic monitor Module * disable
            else if (cmdtrim.startsWith("diagnostic monitor Module ") && line.endsWith(" disable")) {
                output = "no " + line;
                output = output.replace(" disable", "").replace("no no ", "");
            }

            //
            // no-enable -> no enable
            //
            else if (cmdtrim.startsWith("cts server test ") && trimmed.endsWith(" no-enable")) {
                output = line.replace(" no-enable", " enable");
                if (output.startsWith("no ")) {
                    output = output.substring(3);
                } else {
                    output = "no "+output;
                }
            }

            //
            // ip access-list unordered standard|extended *
            //
            else if (cmdtrim.startsWith("ip access-list unordered ")) {
                output = line.replace(" unordered", "");

                // Duplicate name check with unordered list entry [RT36535]
                if (line.startsWith("ip access-list ")
                    && (match = getMatch(output, "ip access-list (?:standard|extended) (\\S+)")) != null) {
                    // Make sure that an standard|extended access-list entry does not exist with same name
                    String path = confRoot+"ip/access-list/standard/std-named-acl{"+match+"}";
                    if (output.startsWith("ip access-list extended ")) {
                        path = confRoot+"ip/access-list/extended/ext-named-acl{"+match+"}";
                    }
                    if (maapiExists(worker, toTh, path)) {
                        throw new NedException("'"+line+"' : Name conflict with an IP access list");
                    }
                }
            }

            // Duplicate name check with unordered list entry [RT36535]
            // ip access-list standard|extended *
            //
            else if (!isDry && line.startsWith("ip access-list ")
                     && (match = getMatch(line, "access-list (?:standard|extended) (\\S+)")) != null) {
                // Make sure that an unordered access-list entry does not exist with same name
                String path = confRoot+"ip/access-list/unordered{"+match+"}";
                if (maapiExists(worker, toTh, path)) {
                    throw new NedException("'"+line+"' : Name conflict with an unordered IP access list");
                }
            }

            //
            // ip access-list standard * / remark [support quoted strings per key word]
            //
            else if (toptag.startsWith("ip access-list standard ") && cmdtrim.startsWith("remark ")
                     && cmdtrim.contains("\"")) {
                String[] tokens = line.split(" +");
                output = "";
                for (int t = 0; t < tokens.length; t++) {
                    output += (" " + stringDequote(tokens[t]));
                }
            }

            //
            // ip forward-protocol udp
            //
            else if (line.contains("ip forward-protocol udp ") && line.contains(" disabled")) {
                line = line.replace(" disabled", "");
                if (line.contains("no ip")) {
                    output = line.replace("no ip", "ip");
                } else {
                    output = "no " + line;
                }
            }

            //
            // no mpls ip propagate-ttl forwarded
            //
            else if ("mpls ip propagate-ttl forwarded".equals(line)) {
                output = "mpls ip propagate-ttl";
            }

            //
            // network-clock-participate wic *
            //
            else if (line.startsWith("network-clock-participate wic wic-disabled ")) {
                output = line.replace("network-clock-participate wic wic-disabled ",
                                      "no network-clock-participate wic ");
            } else if (line.startsWith("no network-clock-participate wic wic-disabled ")) {
                output = line.replace("no network-clock-participate wic wic-disabled ",
                                      "network-clock-participate wic ");
            }

            // no network-clock-participate slot *
            else if (line.startsWith("network-clock-participate slot slot-disabled ")) {
                output = line.replace("network-clock-participate slot slot-disabled ",
                                      "no network-clock-participate slot ");
            } else if (line.startsWith("no network-clock-participate slot slot-disabled ")) {
                output = line.replace("no network-clock-participate slot slot-disabled ",
                                      "network-clock-participate slot ");
            }

            //
            // policy-map * / class * / police - bpsflat (catalyst style)
            //
            else if (toptag.startsWith("policy-map ")
                     && hasPolice("bpsflat") && cmdtrim.startsWith("police ")) {
                output = line.replaceAll("police (\\d+) bps (\\d+) byte", "police $1 $2");
            }

            //
            // no ip ssh server|client algorithm mac .*
            //
            else if (line.matches("^no ip ssh (server|client) algorithm mac .*$")) {
                output = line.substring(0,line.indexOf("mac")+3);
                output = output.replace("no", "default");
            }

            //
            // no ip ssh server|client algorithm encryption .*
            //
            else if (line.matches("^no ip ssh (server|client) algorithm encryption .*$")) {
                output = line.substring(0,line.indexOf("encryption")+10);
                output = output.replace("no", "default");
            }

            //
            // ip mroute-cache
            //
            else if (line.matches("^\\s*ip mroute-cache$") && this.useIpMrouteCacheDistributed) {
                output = line + " distributed";
            }

            //
            // monitor session * filter vlan *
            // monitor session * source vlan *
            // monitor session * source remote vlan *
            // monitor session * destination remote vlan *
            //
            else if (line.contains("monitor session") && line.contains(" vlan ")) {
                output = line.replace(","," , ").replace("-"," - ");
            }

            //
            // controller SONET * / sts-1 "x - y" mode sts-3c
            //
            else if (toptag.startsWith("controller ") && cmdtrim.startsWith("sts-1 ")) {
                output = line.replace("\"", "");
            }

            //
            // crypto pki certificate chain * / certificate *
            // crypto pki certificate pool / certificate *
            // crypto ca certificate chain * / certificate *
            //
            else if ((toptag.startsWith("crypto pki certificate ") || toptag.startsWith("crypto ca certificate "))
                     && line.startsWith(" certificate ")
                     && nextline.trim().startsWith("\"")) {
                // Add certificate line and dequote certificate
                traceVerbose(worker, "transformed => dequoted '"+trimmed+"'");
                sb.append(lines[n++]+"\n");
                lines[n] = stringDequote(lines[n].trim()); // note: prompt shows after each line
            }

            //
            // crypto key pubkey-chain rsa / addressed-key * / key-string
            // crypto keyring * / rsa-pubkey address * / key-string
            //
            else if (line.startsWith("  key-string") &&
                     (toptag.startsWith("crypto key pubkey-chain rsa") || toptag.startsWith("crypto keyring "))
                     && nextline.trim().startsWith("\"")) {
                // Add key-string line and dequote key
                traceVerbose(worker, "transformed => dequoted '"+trimmed+"'");
                sb.append(lines[n++]+"\n");
                lines[n] = stringDequote(lines[n].trim()); // note: prompt shows after each line
            }

            //
            // interface * / no ipv6 nd inspection vlan
            //
            else if (toptag.startsWith("interface ")
                     && trimmed.matches("^no ipv6 nd inspection vlan( add)? \\d+.*$")) {
                // Remove entry
                line = line.replace(" add", "");
                output = line.replace("no ipv6 nd inspection vlan",
                                      "ipv6 nd inspection vlan remove");
            }

            //
            // interface * / service instance * / no encapsulation dot1q X second-dot1q ?
            // interface * / service instance * / encapsulation dot1q X
            //
            else if (toptag.startsWith("interface ")
                     && (match = getMatch(line, "^  no (encapsulation dot1q \\d+) second-dot1q \\d+$")) != null
                     && nextline.trim().equals(match)) {
                traceVerbose(worker, "transformed => stripped '"+line+"'");
                continue;
            }

            //
            // interface * / bridge-group
            //
            else if (toptag.startsWith("interface ")
                     && (match = getMatch(trimmed, "^no bridge-group (\\d+)$")) != null) {
                // Strip all but the first top-list delete
                sb.append(lines[n++]+"\n");
                for (; n < lines.length; n++) {
                    if (lines[n].contains(" bridge-group " + match)) {
                        traceVerbose(worker, "transformed => stripped '"+lines[n].trim()+"'");
                        continue;
                    }
                    break;
                }
            }

            //
            // no snmp-server enable traps
            //
            else if (apiSnmpServerEnableAllTraps == 0
                     && line.startsWith("no snmp-server enable traps ")
                     && !maapiListExists(worker, toTh, confRoot+"snmp-server/enable/traps")) {
                sb.append("default snmp-server enable traps\n");
                int compressed = 1;
                for (; n < lines.length - 1; n++) {
                    if (!lines[n+1].startsWith("no snmp-server enable traps ")) {
                        break;
                    }
                    compressed++;
                }
                traceInfo(worker, "transformed => compressed delete of "+compressed+" snmp-server enable traps");
                continue;
            }

            //
            // voice translation-rule * / rule
            //
            else if (toptag.startsWith("voice translation-rule ") && cmdtrim.startsWith("rule ")
                     && (group = getMatches(trimmed, "rule (\\d+) ((?:[\"])?[/].*?[/](?:[\"])?)"
                                            +" ((?:[\"])?[/].*?[/](?:[\"])?)")) != null
                     && Integer.parseInt(group[0]) == 3) {
                String matchingP = passwordDequote(group[2]);
                String replacementP = passwordDequote(group[3]);
                output = " rule "+group[1]+" "+matchingP+" "+replacementP;
            }

            //
            // connect * / xconnect
            // interface * / xconnect
            // interface * / service instance * ethernet / xconnect
            // interface CEM* / cem * / xconnect
            // interface ATM* / pvc * / xconnect
            //
            else if (trimmed.startsWith("no xconnect ") && "no exit".equals(nextline.trim())) {
                traceInfo(worker, "transformed => stripped invalid 'no exit' [NSO-PATCH]");
                lines[n+1] = "";
            } else if (toptag.startsWith("interface ")
                       && getMatch(trimmed, "^xconnect (\\S+) \\d+ pw-class \\S+$") != null
                       && "exit".equals(nextline.trim())) {
                traceInfo(worker, "transformed => stripped 'exit' due to IOS anomaly, ignoring mode");
                lines[n+1] = "";
            }

            //
            // route-map * / set community *
            //
            else if (toptag.startsWith("route-map ") && trimmed.startsWith("set community ")) {
                String[] token = trimmed.split(" +");
                for (int base = 2; base < token.length; base += 10) {
                    line = " set community";
                    for (int i = base; i < base+10 && i < token.length; i++) {
                        if (!"additive".equals(token[i])) {
                            line += (" " + token[i]);
                        }
                    }
                    if (" set community".equals(line)) {
                        break; // last entry was a non-added additive only
                    }
                    if (trimmed.contains(" additive")) {
                        sb.append(line+" additive\n");
                    } else {
                        sb.append(line+"\n");
                    }
                }
                traceVerbose(worker, "transformed => formatted '"+trimmed+"'");
                continue;
            }

            //
            // route-map * / set extcommunity rt *
            //
            else if (toptag.startsWith("route-map ")
                     && trimmed.startsWith("set extcommunity rt ") && trimmed.endsWith(" additive")
                     && trimmed.length() > 100) {
                String[] rts = trimmed.split(" ");
                int base;
                for (base = 3; base < rts.length-1; base += 10) {
                    sb.append(" set extcommunity rt");
                    for (int i = base; i < base+10 && i < rts.length-1; i++) {
                        sb.append(" " + rts[i]);
                    }
                    sb.append(" additive\n");
                }
                if (base > 3) {
                    traceVerbose(worker, "transformed => split '"+trimmed+"'");
                }
                continue;
            }

            //
            // showOffline:
            // cached-show *
            //
            else if (cmdtrim.startsWith("cached-show ")) {
                continue; // Silent discard
            }


            //
            // Transform lines[n] -> XXX
            //
            if (output != null && !output.equals(lines[n])) {
                final String in = toptag.isEmpty() ? "" : (" in "+toptag);
                if (output.isEmpty()) {
                    traceVerbose(worker, "transformed => stripped '"+trimmed+"'"+in);
                    continue;
                }
                traceVerbose(worker, "transformed => '"+trimmed+"' to '"+output.trim()+"'"+in);
                sb.append(output+"\n");
            } else if (lines[n] != null && !lines[n].isEmpty()) {
                sb.append(lines[n]+"\n");
            }
        }
        return "\n" + sb.toString();
    }


    /**
     * Modify output before sending to device
     * @param
     * @return
     * @throws NedException
     */
    private String modifyOutput(NedWorker worker, String data, String function) throws NedException {

        traceInfo(worker, "Transforming output config...");
        final long start = tick(0);
        NedProgress.Progress progress = reportProgressStart(this, NedProgress.TRANSFORM_OUT);
        int fromTh = worker.getFromTransactionId();
        int toTh = worker.getToTransactionId();
        this.isDry = "PREPARE-DRY".equals(function);
        try {

            // Attach to CDB
            maapiAttach(worker, fromTh, toTh);

            // Reset timeout to NED standard
            lastTimeout = setReadTimeout(worker);

            // Init NedDiff
            setupNedDiff(worker);

            // Clean NSO output
            data = modifyNsoOutput(worker, data);

            // Inject unlock|relock to unlock locked config (before parseCLIDiff to only inject current)
            data = locks.inject(worker, data, toTh, fromTh, relock);

            // Trigger custom extensions (see yang + IOSCliExtensions)
            traceInfo(worker, "turbo-mode parsing...");
            final long startTurbo = tick(0);
            this.outputData = "\n" + data;
            this.extInjectFirst = new StringBuilder();
            this.extInjectLast = new StringBuilder();
            this.extBgpNbrRedeploy = new HashMap<>();
            this.extSentAction = new ArrayList<>();
            data = parseCLIDiff(worker, data+"!!XYZEND\n").replace("!!XYZEND\n", "");
            maapiAttach(worker, fromTh, toTh); // Re-attach transaction
            data = this.extInjectFirst.toString() + data + this.extInjectLast.toString();
            traceInfo(worker, "turbo-mode parsing done "+tickToString(startTurbo));

            // Apply router bgp * / neighbor redeploy changes
            data = outputBgpNbrRedeploy(worker, data, toTh);

            // Trim output
            data = trimOutput(worker, data, toTh);

            // Reorder output
            data = reorderOutput(worker, data);

            // modify access-list
            if (resequenceACL) {
                traceVerbose(worker, "Resequencing ip access-lists");
                data = nedAcl.modify(worker, data, fromTh, toTh);
            }

            // Line-by-line transformations
            if (isNetsim()) {
                data = modifyOutputLineNetsim(data);
            } else {
                data = modifyOutputLine(worker, data, fromTh, toTh);
            }

            // Trim empty interface if deleted in same transaction and (optionally) use 'interface range'
            // Note: Must be after NedDiff or interface dependencies will be broken
            data = trimInterfaces(worker, data, fromTh);

            // Compress output
            data = compressOutput(worker, data);

            // write/replace-commit ned-setting - replace/filter config in commit
            if (!replaceCommit.isEmpty()) {
                traceVerbose(worker, "applying write/replace-commit ned-setting");
                data = replaceCommitData(worker, data);
            }

            // write/inject-command ned-setting - inject command(s) [OLD API]
            if (!injectCommand.isEmpty()) {
                traceVerbose(worker, "applying write/inject-command ned-setting");
                for (int n = 0; n < injectCommand.size(); n++) {
                    String[] entry = injectCommand.get(n);
                    data = injectData(worker, data.trim() + "\n", entry, "=>");
                }
            }

            // expand inject meta-data (injected to avoid NedDiff)
            data = expandMetaDataInject(worker, data);

            // Done
            traceInfo(worker, "Transforming output config done "+tickToString(start));
            reportProgressStop(progress);
            return data.trim() + "\n";

        } catch (Exception e) {
            reportProgressStop(progress, NedProgress.ERROR);
            throw e;

        } finally {
            maapiDetach(worker, fromTh, toTh);
        }
    }


    /**
     * Modify NSO output data
     * @param
     * @return
     * @throws NedException
     */
    private String modifyNsoOutput(NedWorker worker, String data) throws NedException {

        //
        // (1) SECRETS pre-reordering due to NSO bug
        //
        NedDiff preDiff = new NedDiff(this, this.devTraceEnable);
        preDiff.add("^no username <STAY> :: before :: ^username <STAY> .+$");
        data = preDiff.reorder(worker, data);

        // (2) NSO clean line-by-line
        String toptag = "";
        StringBuilder sb = new StringBuilder();
        String[] lines = data.split("\n");
        for (int n = 0; n < lines.length; n++) {
            final String line = lines[n];
            if (line.isEmpty()) {
                sb.append("\n");
                continue;
            }
            final String trimmed = line.trim();
            final String nextline = (n + 1 < lines.length) ? lines[n+1] : "";

            // Update toptag
            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = line; // output, no trim needed
            }

            // Trim duplicate 'no username *'
            if (line.startsWith("no username ") && line.equals(nextline)) {
                traceInfo(worker, "transformed => stripped duplicate '"+nextline+"' [NSO-PATCH]");
                continue;
            }

            // Trim duplicate interface * / no switchport
            else if (toptag.startsWith("interface ")
                     && "no switchport".equals(trimmed) && "no switchport".equals(nextline.trim())) {
                traceInfo(worker, "transformed => stripped duplicate '"+toptag+" / no switchport' [NSO-PATCH]");
                continue;
            }

            // interface * / vrf forwarding - trim address sets before the vrf change
            else if (isDevice() && line.startsWith("interface ")) {
                int vrfIndex = n;
                for (int v = n + 1; v < lines.length; v++) {
                    if (isTopExit(lines[v])) {
                        break;
                    }
                    if (lines[v].trim().matches("^(no )?(ip )?vrf forwarding \\S+$")) {
                        vrfIndex = v;
                        break;
                    }
                }
                for (int v = n + 1; v < vrfIndex; v++) {
                    if (lines[v].startsWith(" ip address ") || lines[v].startsWith(" ipv6 address ")) {
                        traceDev(worker, "transformed => stripped '"+lines[v]+"' with if-vrf change");
                        lines[v] = ""; // trim previous ip address additions
                    }
                }
                // fall through to add interface line
            }

            // Add line
            sb.append(lines[n]+"\n");
        }

        return sb.toString();
    }


    /**
     * Act on info cached by 'bgp-nbr-redeploy-trigger' to redeploy neighbor entries when peer-group is changed
     * @param
     * @return
     */
    private String outputBgpNbrRedeploy(NedWorker worker, String data, int toTh) {

        if (this.extBgpNbrRedeploy.isEmpty()) {
            return data;
        }

        String[] lines = data.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if (line.isEmpty()) {
                sb.append("\n");
                continue;
            }
            if (!line.startsWith("router bgp ")) {
                sb.append(line+"\n");
                continue;
            }

            //
            // router-bgp * - Trim BGP neighbor changes and redeploy with peer-group|remote-as change
            //
            StringBuilder addrsb = new StringBuilder();
            for (; n < lines.length; n++) {
                line = lines[n];
                String nbr = bgpNbrContains(line.trim());
                if (nbr == null) {
                    if (isTopExit(line)) {
                        sb.append(addrsb.toString()+line+"\n");
                        break;
                    }
                    sb.append(line+"\n");
                } else if (line.startsWith(" "+nbr+"peer-group ") || line.startsWith(" "+nbr+"remote-as ")) {
                    // Delete and redeploy router bgp * / neighbor *
                    final String path = this.extBgpNbrRedeploy.get(nbr) + "/../";
                    traceVerbose(worker, "BGP: neighbor redeploy: "+line);
                    sb.append(" no "+nbr+"\n");
                    String inject = maapiGetConfig(worker, toTh, path, 1);
                    sb.append(inject);

                    // Redeploy router bgp * / address-family ipv4 unicast / neighbor *
                    final String addr = nbr.substring(nbr.indexOf(' ')+1);
                    final String bgpAfPath = path.substring(0, path.indexOf("/neighbor")+1) + "address-family/";
                    addrsb.append(bgpNbrRedeploy(worker,toTh,bgpAfPath+"ipv4{unicast}/neighbor{"+addr+"}"));
                    addrsb.append(bgpNbrRedeploy(worker,toTh,bgpAfPath+"vpnv4{unicast}/neighbor{"+addr+"}"));
                    addrsb.append(bgpNbrRedeploy(worker,toTh,bgpAfPath+"ipv6{unicast}/neighbor{"+addr+"}"));
                    addrsb.append(bgpNbrRedeploy(worker,toTh,bgpAfPath+"vpnv6{unicast}/neighbor{"+addr+"}"));
                } else {
                    // Trim all non peer-group|remote-as 'router bgp * / * / neighbor *'
                    traceDev(worker, "transformed => BGP trimmed '"+line+"'");
                }
            }
        }

        return sb.toString();
    }


    /**
     * outputBgpNbrRedeploy do'er
     * @param
     * @return
     */
    private String bgpNbrRedeploy(NedWorker worker, int toTh, String path) {
        if (!maapiExists(worker, toTh, path)) {
            return "";
        }
        traceVerbose(worker, "BGP: redeployed "+shortpath(path));
        return maapiGetConfig(worker, toTh, path, 1);
    }


    /**
     * outputBgpNbrRedeploy do'er
     * @param
     * @return
     */
    private String bgpNbrContains(String line) {
        Iterator it = this.extBgpNbrRedeploy.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry)it.next();
            String nbr = (String)pair.getKey();
            if (line.startsWith(nbr)) {
                return nbr;
            }
            if (line.startsWith(" no"+nbr)) {
                return nbr;
            }
        }
        return null;
    }


    /**
     * Trim interface exit and re-enter
     * Trim deleted empty interface changes
     * Compact interface changes using 'interface range' command
     * @param
     * @return
     */
    private String trimInterfaces(NedWorker worker, String data, int fromTh) {

        //
        // Pass 1
        //
        StringBuilder sb = new StringBuilder();
        String[] lines = data.split("\n");
        String toptag = "";
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if (line.isEmpty()) {
                sb.append("\n");
                continue;
            }
            final String nextline = (n + 1 < lines.length) ? lines[n+1] : "";

            // Trim pointless 'control-plane'
            if ("control-plane".equals(line) && "!".equals(nextline)
                && maapiExists(worker, fromTh, confRoot+"control-plane")) {
                traceVerbose(worker, "transformed => trimmed empty control-plane");
                n = n + 1;
                continue;
            }

            // Trim in and out of same interface
            if ("exit".equals(line)
                && toptag.startsWith("interface ") && nextline.equals(toptag)) {
                traceVerbose(worker, "transformed => trimmed '"+toptag+"' exit and re-enter");
                n = n + 1;
                continue;
            }

            // Update toptag
            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = line; // output, no trim needed
            }

            sb.append(line+"\n");
        }
        data = sb.toString();

        //
        // Pass 2
        //
        sb = new StringBuilder("\n");
        lines = data.split("\n");
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            String nextline = (n + 1 < lines.length) ? lines[n+1] : "";
            String nextline2 = (n + 2 < lines.length) ? lines[n+2] : "";

            // Trim empty interface
            if (line.startsWith("interface ") && "exit".equals(nextline)
                && (data.contains("\nno "+line+"\n") || sb.indexOf("\n"+line+"\n") >= 0)) {
                traceVerbose(worker, "transformed => trimmed empty '"+line+"'");
                n++; // Also skip the "exit"
            }

            // Trim empty interface (with commment)
            else if (line.startsWith("interface ")
                     && nextline.trim().startsWith("!") && "exit".equals(nextline2)
                     && data.contains("\nno "+line+"\n")) {
                traceVerbose(worker, "transformed => trimmed empty '"+line+"'");
                n = n + 2; // Also skip the comment and the "exit"
            }

            // Add line
            else {
                sb.append(line+"\n");
            }
        }
        data = sb.toString();
        if (!autoIfRangeWrite || isNetsim()) {
            return data;
        }

        //
        // pass 3 - ned-settings cisco-ios auto interface-range-write"
        //
        sb = new StringBuilder("\n");
        lines = data.split("\n");
        int next;
        for (int n = 0; n < lines.length; n = next) {
            next = n + 1;
            String line = lines[n];
            String iftype;
            if (!line.startsWith("interface ") || (iftype = getInterfaceType(line)) == null) {
                sb.append(line+"\n");
                continue;
            }

            // Only allow existing interfaces in range path (ignore Ethernet & GigE without ".")
            boolean staticIf = (iftype.contains("Ethernet") || iftype.contains("GigE")) && !line.contains(".");
            if (!staticIf) {
                final String ifpath = getInterfacePath(line);
                if (ifpath == null || !maapiExists(worker, fromTh, ifpath)) {
                    sb.append(line+"\n");
                    continue;
                }
            }

            traceVerbose(worker, "applying auto/interface-range-write: '"+line+"' type="+iftype);

            // (1) Extract interface prefix and start index
            final String regex = "(interface "+iftype+"(?:\\d+[/])*(?:\\d+[.])?)(\\d+)$";
            Pattern p = Pattern.compile(regex);
            Matcher m = p.matcher(line);
            if (!m.find()) {
                traceVerbose(worker, "   WARNING: start regex failed for '"+line+"'");
                sb.append(line+"\n");
                continue;
            }
            final String prefix = m.group(1);
            int start = Integer.parseInt(m.group(2));
            traceDev(worker, "   "+line+" [prefix = '"+prefix+"' start = "+start+"]");

            // Get first interface config (excluding interface 'header' line)
            StringBuilder configsb = new StringBuilder();
            next = linesGetBlock(lines, n + 1, configsb);

            // For some obscure reason ' service instance * ethernet' can not be modified with 'interface range'
            boolean haveEthServiceInstance = false;
            for (int i = n + 1; i < next; i++) {
                if (lines[i].startsWith(" service instance ") || lines[i].startsWith(" no service instance ")) {
                    haveEthServiceInstance = true;
                    break;
                }
            }
            if (haveEthServiceInstance) {
                traceVerbose(worker, "   ignored, contains 'service instance * ethernet' change");
                sb.append(line+"\n");
                sb.append(configsb);
                continue;
            }

            // (2) Get all similar interfaces with identical sub-mode config
            SortedMap<Integer, String> sm = new TreeMap<>();
            for (;next < lines.length;) {
                if (!lines[next].startsWith(prefix)) {
                    break;
                }

                // Get prefix and index
                m = p.matcher(lines[next]);
                if (!m.find()) {
                    traceVerbose(worker, "   WARNING: end regex failed for '"+lines[next]+"'");
                    break;
                }
                final int end = Integer.parseInt(m.group(2));
                traceDev(worker, "   "+lines[next]+" [end = "+end+"]");
                if (!prefix.equals(m.group(1))) {
                    traceVerbose(worker, "     interface prefix diff, ending");
                    break;
                }

                // Only allow existing interfaces in range path
                if (!staticIf) {
                    final String nextifpath = getInterfacePath(lines[next]);
                    if (nextifpath == null || !maapiExists(worker, fromTh, nextifpath)) {
                        traceVerbose(worker, "     interface created, ending");
                        break;
                    }
                }

                // Get interface sub-mode config and compare with start interface
                StringBuilder nextsb = new StringBuilder();
                final int nextnext = linesGetBlock(lines, next + 1, nextsb);
                if (!configsb.toString().equals(nextsb.toString())) {
                    traceVerbose(worker, "     interface config diff, ending");
                    break;
                }

                // Add to SortedMap, save lines[] index for non-consecutive interfaces
                sm.put(new Integer(end), lines[next]);
                next = nextnext;
            }

            // (3) Form interface groups
            int end = start;
            SortedMap<Integer, Integer> smg = new TreeMap<>();
            Iterator it = sm.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry map = (Map.Entry)it.next();
                int index = (Integer)map.getKey();
                if (end + 1 == index) {
                    // consecutive range
                    end++;
                } else {
                    // new range
                    smg.put(Integer.valueOf(start), Integer.valueOf(end));
                    start = end = index;
                }
            }
            smg.put(Integer.valueOf(start), Integer.valueOf(end));

            // (4) Flush interface groups
            StringBuilder outsb = new StringBuilder();
            it = smg.entrySet().iterator();
            while (it.hasNext()) {
                // Get group and increase count
                Map.Entry map = (Map.Entry)it.next();
                start = (Integer)map.getKey();
                end = (Integer)map.getValue();
                traceDev(worker, "   group start = "+start+", end = "+end);

                // Create group (range) line
                line = prefix.replace("interface ", "") + start;
                if (end > start) {
                    String post = "";
                    if (prefix.endsWith(".")) {
                        // Sub-interface syntax: 'GigabitEthernet 2.1 - GigabitEthernet 2.2'
                        post = prefix.replace("interface ", "").replace(iftype, iftype+" ");
                    }
                    line += " - " + post + end;
                }
                traceDev(worker, "   group line = '"+line+"'");

                // Flush output buffer if too long or if sub-interface, else append group
                if (outsb.length() + line.length() > 200
                    || (outsb.length() > 0 && prefix.endsWith("."))) {
                    interfaceRangeFlush(worker, sb, outsb, configsb);
                    outsb = new StringBuilder();
                } else if (outsb.length() > 0) {
                    outsb.append(" , ");
                }
                outsb.append(line);
                traceDev(worker, "   group outsb = '"+outsb.toString()+"'");
            }

            // (5) Flush (last) output buffer
            if (outsb.length() > 0) {
                interfaceRangeFlush(worker, sb, outsb, configsb);
            }
        }

        return sb.toString();
    }


    /**
     * Add interface [range] to output data
     * @param
     * @return
     */
    private void interfaceRangeFlush(NedWorker worker, StringBuilder sb, StringBuilder outsb, StringBuilder configsb) {
        sb.append("interface ");
        if (outsb.indexOf(" - ") > 0 || outsb.indexOf(" , ") > 0) {
            sb.append("range ");
            traceInfo(worker, "transformed => auto/interface-range-write: '"+outsb.toString()+"'");
        }
        sb.append(outsb);
        sb.append("\n");
        sb.append(configsb);
    }


    /**
     * Get all config lines up to top exit and return next index
     * @param
     * @return
     */
    private int linesGetBlock(String[] lines, int n, StringBuilder sb) {
        for (; n < lines.length; n++) {
            sb.append(lines[n]+"\n");
            if (isTopExit(lines[n])) {
                return n + 1;
            }
        }
        return lines.length;
    }


    /**
     * Get interface range type
     * @param
     * @return
     */
    private String getInterfaceType(String line) {
        String[] interfaces = {
            "ATM",
            "BDI",
            "BD-VIF",
            "FastEthernet",
            "GigabitEthernet",
            "TwoGigabitEthernet",
            "TenGigabitEthernet",
            "TwentyFiveGigE",
            "FortyGigabitEthernet",
            "HundredGigE",
            "LongReachEthernet",
            "Loopback",
            "Port-channel", // special path
            "Tunnel",
            "Vlan"
        };
        for (int n = 0; n < interfaces.length; n++) {
            if (line.startsWith("interface "+interfaces[n])) {
                return interfaces[n];
            }
        }
        return null;
    }


    /**
     * Return interface path for interfaces supporting range
     * @param
     * @return
     */
    private String getInterfacePath(String line) {
        String iftype = getInterfaceType(line);
        if (iftype == null) {
            return null;
        }
        String key = getMatch(line, "interface "+iftype+"(\\S+)");
        if (key == null) {
            return null;
        }

        // sub-interface with special path
        if ("Port-channel".equals(iftype) && line.contains(".")) {
            return this.confRoot+"interface/Port-channel-subinterface/Port-channel{"+key+"}";
        }

        // main interface or sub-interface in same path
        return this.confRoot+"interface/"+iftype+"{"+key+"}";
    }


    /**
     * Expand meta-data inject tag contents
     * @param
     * @return
     */
    private String expandMetaDataInject(NedWorker worker, String data) {
        StringBuilder sb = new StringBuilder();
        String[] lines = data.split("\n");
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if (line.startsWith(META_DATA) && line.contains(" :: inject :: ")) {
                traceDev(worker, "expanded inject config: "+stringQuote(line));
                String[] metas = line.split(" :: ");
                String[] inject = metas[3].split("<lf>");
                for (int i = 0; i < inject.length; i++) {
                    sb.append(inject[i]+"\n");
                }
                continue;
            }
            sb.append(line+"\n");
        }
        return sb.toString();
    }


    /**
     * Trim output data, e.g. delete of (large) ip prefix-list lists
     * @param
     * @return
     */
    private String trimOutput(NedWorker worker, String data, int toTh) {
        String match;
        String[] lines = data.split("\n");
        StringBuilder sb = new StringBuilder("\n");
        String ipPfxPath = null;
        String toptag = "";
        data = "\n" + data;
        String trimRouterBgp = getMatch(data, "\nno (router bgp \\d+)");
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if (line.isEmpty()) {
                sb.append("\n");
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = line;
            }

            // router bgp * / no neighbor * remote-as|activate|peer-group
            // router bgp * / address-family * / no neighbor * remote-as|activate|peer-group
            if (toptag.startsWith("router bgp ")
                && trimmed.startsWith("no neighbor ")
                && (match = getMatch(line, "([ ]+no neighbor \\S+) (?:remote-as|activate|peer-group)")) != null) {
                // Compress neighbor delete if remote-as|activate|peer-group are deleted
                traceVerbose(worker, "transformed => compacted '"+line+"'");
                for (; n < lines.length - 1; n++) {
                    if (lines[n+1].startsWith(match+" ")) {
                        continue; // no neighbor <id> <leaf>
                    }
                    break;
                }
            }

            // no router bgp *
            else if (trimRouterBgp != null && line.startsWith(trimRouterBgp)) {
                traceVerbose(worker, "transformed => trimmed deleted '"+line+"' changes");
                for (n = n + 1; n < lines.length; n++) {
                    if (isTopExit(lines[n])) {
                        break;
                    }
                }
                continue;
            }

            // no ip prefix-list <name> seq <entry>
            // Note: ignore description lines since deleted separately
            else if (line.startsWith("no ip prefix-list ") && !line.contains(" description ")) {

                // Get ip prefix-list root path
                if (ipPfxPath == null) {
                    String seqNo = maapiGetLeafString(worker, toTh, confRoot+"ip/prefix-list/sequence-number");
                    traceVerbose(worker, "ip prefix-list sequence-number = "+ seqNo);
                    if (seqNo != null && "false".equals(seqNo)) {
                        ipPfxPath = confRoot+"ip/prefix-list/prefixes-no-seq";
                    } else {
                        ipPfxPath = confRoot+"ip/prefix-list/prefixes";
                    }
                }

                // Compress prefix-list seq delete
                String name;
                if ((name = getMatch(line, "no ip prefix-list (\\S+) ")) != null
                    && !maapiExists(worker, toTh, ipPfxPath+"{"+name+"}")) {
                    int num = 1;
                    for (int t = n + 1; t < lines.length; t++) {
                        if (!lines[t].startsWith("no ip prefix-list "+name+" ")) {
                            break;
                        }
                        lines[t] = "";
                        num++;
                    }
                    if (num > 1) {
                        traceInfo(worker, "transformed => compressed "+num+" 'no ip prefix-list "+name+"' lines");
                        sb.append("no ip prefix-list "+name+"\n");
                        continue;
                    }
                }
            }

            // interface * / no shutdown
            else if (" no shutdown".equals(line)
                     && toptag.startsWith("interface Vlan")
                     && data.contains("\nno "+toptag+"\n")) {
                // Trim 'no shutdown' if interface is deleted in same transaction
                traceVerbose(worker, "transformed => trimmed no shutdown in "+toptag);
                continue;
            }

            // ip ddns update method * / interval
            else if (line.startsWith(" interval minimum ")) {
              Pattern p = Pattern.compile("^\\s*interval minimum \\d+$");
              Matcher m = p.matcher(line);
              line = m.find() ? "" : line;
            }

            // Add line
            sb.append(line+"\n");
        }

        return sb.toString();
    }


    /**
     * Reorder output data
     * @param
     * @return
     * @throws NedException
     */
    private String reorderOutput(NedWorker worker, String data) throws NedException {

        String[] lines = data.split("\n");

        //
        // Pass 1 - reorder top mode config (e.g. routes, interfaces etc)
        //
        StringBuilder first = new StringBuilder();
        StringBuilder middle = new StringBuilder();
        StringBuilder last = new StringBuilder();
        String match;
        String toptag = "";
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if (line.isEmpty()) {
                continue;
            }
            String trimmed = line.trim();
            String cmdtrim = trimmed.startsWith("no ") ? trimmed.substring(3) : trimmed;

            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = line; // Note: no trim needed due to output and no trailing \r
            }

            // Routes should always be deleted first and added last [CISCOIOS-1105]
            if (line.startsWith("no ip route ") || line.startsWith("no ipv6 route ")) {
                traceVerbose(worker, "transformed => moved '"+line+"' first (route)");
                first.append(line+"\n");
            } else if (line.startsWith("ip route ") || line.startsWith("ipv6 route ")) {
                traceVerbose(worker, "transformed => moved '"+line+"' last (route)");
                last.append(line+"\n");
            }

            // Always delete main interface channel-group first (or sub-interface not accessible)
            else if (toptag.startsWith("interface ")
                     && toptag.matches("^interface [A-Za-z0-9/]+$")
                     && line.startsWith(" no channel-group ")) {
                traceVerbose(worker, "transformed => moved '"+toptag+" /"+line+"' first");
                first.append(toptag+"\n"+line+"\nexit\n");
            }

            // Always delete LISP interfaces last
            else if (line.startsWith("no interface LISP")) {
                traceVerbose(worker, "transformed => moved '"+line+"' last (lisp)");
                last.append(line+"\n");
            }

            // Delete vlan ranges last
            else if (line.startsWith("no vlan ") && line.contains("-")) {
                traceVerbose(worker, "transformed => moved '"+line+"' last (vlan range)");
                last.append(line+"\n");
            }

            // Reverse order of line vty deletes [RT24125]
            else if (line.startsWith("no line vty ")) {
                traceVerbose(worker, "transformed => moved '"+line+"' last (reversed)");
                last.insert(0, line+"\n");
            }

            // Restore terminal length
            else if (toptag.startsWith("line ") && cmdtrim.startsWith("length ")) {
                middle.append(line+"\n");
                if (last.indexOf("do terminal length 0\n") < 0) {
                    last.append("do terminal length 0\n");
                }
            }

            // Put delete of ip[v6] prefix-list before create [CISCOIOS-904]
            else if ((line.startsWith("ip prefix-list ") || line.startsWith("ipv6 prefix-list "))
                     && (match = getMatch(line, "(ip(?:v6)? prefix-list \\S+ seq \\d+ )")) != null) {
                for (int p = n + 1; p < lines.length; p++) {
                    if (lines[p].startsWith("no "+match)) {
                        traceVerbose(worker, "transformed => moved '"+lines[p]+"' up");
                        middle.append(lines[p]+"\n");
                        lines[p] = "";
                        break;
                    }
                }
                middle.append(lines[n]+"\n");
            }

            // interface * / no service-policy input|output - non sub-mode interface delete first
            else if (toptag.startsWith("interface ")
                     && (line.startsWith(" no service-policy output ") || line.startsWith(" no service-policy input "))
                     && (match = getMatch(toptag, "(interface [A-Za-z0-9/]+)[.][0-9]+")) != null
                     && middle.indexOf(match+"\n") >= 0) {
                traceVerbose(worker, "transformed => injected sub-interface '"+line.substring(4)+"' delete first");
                first.append(toptag+"\n"+line+"\n"+"exit\n");
            }

            // control-plane / no service-policy input|output
            else if ("control-plane".equals(toptag) && line.startsWith(" no service-policy ")) {
                traceVerbose(worker, "transformed => injected control-plane '"+line.substring(4)+"' delete first");
                first.append(toptag+"\n"+line+"\n"+"exit\n");
            }

            // no ip sla group schedule *
            // ip sla group schedule * delete
            else if (line.startsWith("no ip sla group schedule ")
                     || getMatch(line, "ip sla group schedule (\\d+) delete") != null) {
                traceVerbose(worker, "transformed => moved '"+line+"' first");
                first.append(line+"\n");
            }

            // no platform smart-sfp interface *
            else if (line.startsWith("no platform smart-sfp interface ")) {
                traceVerbose(worker, "transformed => moved '"+line+"' last");
                last.append(line+"\n");
            }

            // Default case
            else {
                middle.append(line+"\n");

                // Special service-policy policy-map inject patch [CISCOIOS-649]
                if ((match = getMatch(lines[n], "^\\s+service-policy (?:input|output) (\\S+)$")) != null) {
                    boolean found = false;
                    for (int b = n; b >= 0; b--) {
                        if (lines[b].matches("^policy-map(?: type \\S+)? "+match+"$")) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        for (int f = n + 1; f < lines.length; f++) {
                            if (lines[f].matches("^policy-map(?: type \\S+)? "+match+"$")) {
                                traceInfo(worker, "transformed => injected '"+lines[f]
                                          +"' before use in service-policy");
                                first.append(lines[f]+"\nexit\n");
                                break;
                            }
                        }
                    }
                }
            }
        }
        data = "\n" + first.toString() + middle.toString() + last.toString();


        //
        // Pass 2 - assert class order in policy-map
        //
        data = policyMapAssertClassOrder(worker, data);


        //
        // Pass 3 - string buffer swapping
        //
        StringBuilder sb = new StringBuilder();
        lines = data.split("\n");
        toptag = "";
        for (int n = 0; n < lines.length; n++) {
            int swap = 0;
            String line = lines[n];
            String trimmed = lines[n].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String nextline = (n + 1 < lines.length) ? lines[n+1] : "";
            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = trimmed;
            }

            // router ospf * / max-metric router-lsa
            if (toptag.startsWith("router ospf")
                && trimmed.startsWith("max-metric router-lsa ")
                && nextline.trim().startsWith("no max-metric router-lsa ")) {
                swap = 1;
            }

            // router * / distribute-list
            else if (toptag.startsWith("router ")
                     && trimmed.startsWith("distribute-list ")
                     && nextline.trim().startsWith("no distribute-list ")) {
                swap = 2;
            }

            // redistribute ?
            else if ((match = getMatch(nextline, "no redistribute (.*)")) != null
                     && trimmed.startsWith("redistribute "+match+" ")) {
                swap = 3;
            }

            // ip sla * / threshold + timeout
            else if (toptag.startsWith("ip sla ")
                     && trimmed.startsWith("threshold ") && nextline.trim().startsWith("timeout ")
                     && (match = getMatch(trimmed, "threshold[ ]+(\\S+)")) != null
                     && Integer.parseInt(match) > 5000) {
                swap = 4;
            }

            // ip sla * / no timeout + no threshold
            else if (toptag.startsWith("ip sla ")
                     && trimmed.startsWith("no timeout ") && nextline.trim().startsWith("no threshold ")
                     && (match = getMatch(trimmed, "no timeout[ ]+(\\S+)")) != null
                     && Integer.parseInt(match) > 5000) {
                swap = 5;
            }

            // object-group network * / no * + *
            else if (toptag.startsWith("object-group network ")
                     && line.startsWith(" no ")
                     && nextline.startsWith(" ") && !nextline.startsWith(" no ")) {
                swap = 6;
            }

            // Add line and nextline, swapped
            if (swap > 0) {
                traceVerbose(worker, "transformed => swapped["+swap+"] '"+line+"' and '"+nextline+"'");
                sb.append(nextline+"\n");
                sb.append(line+"\n");
                n = n + 1;
                continue;
            }

            // Default
            sb.append(line+"\n");
        }
        data = "\n" + sb.toString();


        //
        // Pass 4 - NedDiff (last, to let user override NED ordering)
        //
        traceVerbose(worker, "DIFF DEPENDENCY RULES:\n"+nedDiff.toString());
        data = nedDiff.reorder(worker, data);


        //
        // Pass 5 - Last minute dirty kludges
        //
        first = new StringBuilder();
        sb = new StringBuilder();
        lines = data.split("\n");
        toptag = "";
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if (line.isEmpty()) {
                sb.append("\n");
                continue;
            }
            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = line;
            }

            // cable fiber-node * / no service-group profile [CISCOIOS-2191]
            if (toptag.startsWith("cable fiber-node ")
                && line.startsWith(" no service-group profile ")) {
                traceVerbose(worker, "transformed => moved service-group profile delete first "+toptag);
                first.append(toptag+"\n"+line+"\n!\n");
                continue;
            }

            // Default
            sb.append(line+"\n");
        }
        data = first.toString() + sb.toString();

        // Reordering complete
        return data;
    }


    /**
     * Assert policy-map * / class * order since value dependencies may re-order classes
     * @param
     * @return
     */
    private String policyMapAssertClassOrder(NedWorker worker, String data) {

        StringBuilder sb = new StringBuilder();
        String[] lines = data.split("\n");
        for (int n = 0; n < lines.length; n++) {

            // Non-policy-map, add
            if (!lines[n].startsWith("policy-map ")) {
                sb.append(lines[n]+"\n");
                continue;
            }

            // Collect entire policy-map
            StringBuilder polsb = new StringBuilder();
            for (; n < lines.length; n++) {
                polsb.append(lines[n]+"\n");
                if (isTopExit(lines[n])) {
                    break;
                }
            }

            // Only pre-inject skeleton policy-map if both create & delete class(es)
            if (polsb.indexOf("\n class ") < 0 || polsb.indexOf("\n no class ") < 0) {
                sb.append(polsb);
                continue;
            }

            // Pre-inject skeleton policy-map (with class create|delete only) to preserve order
            String[] polmap = polsb.toString().split("\n");
            StringBuilder polsb2 = new StringBuilder();
            StringBuilder inject = new StringBuilder(META_DATA+". :: inject :: "+polmap[0]+"<lf>");
            StringBuilder last = new StringBuilder();
            for (int i = 0; i < polmap.length; i++) {
                String line = polmap[i];
                if (line.startsWith(" no class ")) {
                    // Note: 'no class' is stripped from main entry
                    String cm = getMatch(line, " no( class .+)");
                    if (polsb.indexOf("\n"+cm+"\n") >= 0) {
                        inject.append(line+"<lf>");
                    } else {
                        last.append(line+"<lf>");
                    }
                } else {
                    polsb2.append(line+"\n");
                    if (line.startsWith(" class ")) {
                        inject.append(line+"<lf>");
                    }
                }
            }
            inject.append(last);
            inject.append(" !<lf>!\n");

            traceInfo(worker, "transformed => pre-injected skeleton '"+polmap[0]+"' to preserve class order");
            sb.append(inject);
            sb.append(polsb2);
        }

        return sb.toString();
    }


    /**
     * Compress output data
     * @param
     * @return
     * @throws NedException
     */
    @SuppressWarnings("unchecked")
    private String compressOutput(NedWorker worker, String data) throws NedException {

        traceVerbose(worker, "Compressing output");

        // ned-setting api pretty-line-vty-format
        if (prettyLineVtyFormat && expandedLineVtyFormat) {
            data = formatPrettyLineVty(worker, data);
        }

        String[] lines0 = data.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int n = 0; n < lines0.length; n++) {
            String line = lines0[n];

            // (1) Find first line we can compress
            int x = isRangeConfig(line, -1, null);
            if (x == -1) {
                sb.append(line+"\n");
                continue;
            }
            String command = line.replaceFirst(" "+getMatch(line, rangeConfig[x]), " <range>");
            if (command.trim().startsWith("no ")) {
                command = command.replaceFirst("no ", "");
            }
            final String spaces = line.replace(line.trim(), "");

            // (2) Collect all entries in range
            List<String> lines = new ArrayList<>();
            for (; n < lines0.length; n++) {
                if (isRangeConfig(lines0[n], x, command) == x || isSubConfig(spaces, lines0[n])) {
                    lines.add(lines0[n]);
                } else {
                    n = n - 1;
                    break;
                }
            }

            traceDev(worker, "   compressing '"+line+"'  command = '"+command+"'");
            for (String line0 : lines) {
                traceDev(worker, "   <-- "+line0);
            }

            // (3) Sort entries in delete and add with sub-mod
            LinkedHashMap<String,SortedMap<Integer, String>> addmap = new LinkedHashMap<>();
            SortedMap<Integer, String> del = new TreeMap<>();
            for (int i = 0; i < lines.size(); i++) {
                line = lines.get(i);
                traceDev(worker, "line = '"+line+"' x = "+x+" = "+rangeConfig[x]);
                String value = getMatch(line, rangeConfig[x]);
                if (line.startsWith(spaces+"no ")) {
                    // delete
                    del.put(Integer.valueOf(value), "");
                } else {
                    // add - collect optional sub-mode config
                    StringBuilder smb = new StringBuilder();
                    for (; i < lines.size() - 1; i++) {
                        String subline = lines.get(i+1);
                        if (!isSubConfig(spaces, subline)) {
                            break;
                        }
                        smb.append(subline+"\n");
                    }
                    SortedMap<Integer, String> add = addmap.get(smb.toString());
                    if (add == null) {
                        add = new TreeMap<>();
                    }
                    traceDev(worker, "value = "+value+" "+stringQuote(smb.toString()));
                    add.put(Integer.valueOf(value), smb.toString());
                    addmap.put(smb.toString(), add);
                }
            }

            // (4) Run do'er on all sorted lists
            List<String> inject = new ArrayList<>();
            if (!del.isEmpty()) {
                doRangeListSyntax(worker, command, del, inject, false);
            }
            Iterator it = addmap.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry pair = (Map.Entry)it.next();
                SortedMap<Integer, String> add = (SortedMap<Integer, String>)pair.getValue();
                doRangeListSyntax(worker, command, add, inject, true);
            }

            // (5) Add back compressed output
            traceDev(worker, "   compressed '"+inject.get(0)+"'  command = '"+command+"'");
            for (String line0 : inject) {
                traceDev(worker, "   --> "+stringQuote(line0));
                sb.append(line0+"\n");
            }
        }

        return "\n" + sb.toString();
    }


    /**
     * Return index into rangeConfig[] if we can compress, else -1
     * @param
     * @return
     */
    private static final String[] rangeConfig = {
        "^(?:no )?vlan (\\d+)$",  // mode
        "^(?:no )?line vty (\\d+)$", // mode
        "^(?:no )?spanning-tree mst (\\d+) \\S+ \\S+",
        "^(?:no )?spanning-tree vlan (\\d+) \\S+ \\S+$",
        "^(?:no )?spanning-tree vlan (\\d+)$"
    };

    private int isRangeConfig(String line, int x, String command) {

        // Don't compress the following unless enabled in ned-settings:
        if (!autoCompressSpanningTreeVlan && line.contains("spanning-tree vlan ")) {
            return -1;
        }
        if (!expandedLineVtyFormat && line.contains("line vty ")) {
            return -1;
        }

        if (x != -1) {
            Pattern p = Pattern.compile(rangeConfig[x]);
            Matcher m = p.matcher(line);
            if (m.find() && line.replaceFirst(" "+m.group(1), " <range>").contains(command)) {
                return x;
            }
        } else {
            for (int i = 0; i < rangeConfig.length; i++) {
                Pattern p = Pattern.compile(rangeConfig[i]);
                Matcher m = p.matcher(line);
                if (m.find()) {
                    return i;
                }
            }
        }
        return -1;
    }


    /**
     * Return true if line is sub-mode config
     * @param
     * @return
     */
    private boolean isSubConfig(String spaces, String line) {
        if (line.startsWith(spaces+" ")) {
            return true;
        }
        if (line.equals(spaces+"!") || line.equals(spaces+"exit")) {
            return true;
        }
        return false;
    }


    /**
     * Do-er method for compressOutput
     * @param
     * @return
     */
    private void doRangeListSyntax(NedWorker worker, String command, SortedMap<Integer, String> sm,
                                   List<String> inject, boolean create) {
        // (1) Form groups
        int start = (int)sm.firstKey();
        int end = start;
        SortedMap<Integer, Integer> smg = new TreeMap<>();
        Iterator it = sm.entrySet().iterator();
        String config = "";
        while (it.hasNext()) {
            Map.Entry map = (Map.Entry)it.next();
            int index = (Integer)map.getKey();
            config = (String)map.getValue();
            if (end + 1 == index) {
                // consecutive range
                end++;
            } else {
                // new range
                smg.put(Integer.valueOf(start), Integer.valueOf(end));
                start = end = index;
            }
        }
        smg.put(Integer.valueOf(start), Integer.valueOf(end));

        // (2) Flush groups
        StringBuilder rangesb = new StringBuilder();
        it = smg.entrySet().iterator();
        while (it.hasNext()) {
            // Get group and increase count
            Map.Entry map = (Map.Entry)it.next();
            start = (Integer)map.getKey();
            end = (Integer)map.getValue();

            // Create group (range) line
            String range = String.format("%d", start);
            if (end > start) {
                if (command.contains("line vty")) {
                    range += " "+end;
                } else {
                    range += "-"+end;
                }
            }

            // Flush output buffer if too long, else append group
            if (rangesb.length() + range.length() > 200
                || (rangesb.length() > 0 && command.contains("line vty"))) {
                doRangeListSyntaxInject(worker, command, rangesb, config, inject, create);
                rangesb = new StringBuilder();
            } else if (rangesb.length() > 0) {
                rangesb.append(",");
            }
            rangesb.append(range);
        }
        if (rangesb.length() > 0) {
            doRangeListSyntaxInject(worker, command, rangesb, config, inject, create);
        }
    }


    /**
     * Do-er method for doRangeListSyntax
     * @param
     * @return
     */
    private void doRangeListSyntaxInject(NedWorker worker, String command, StringBuilder rangesb, String config,
                                         List<String> inject, boolean create) {
        final String range = rangesb.toString();
        String output = command.replace("<range>", range);
        if (!create) {
            output = output.replace(output.trim(), "no "+output.trim());
        }
        if (range.contains(",") || range.contains("-")) {
            traceVerbose(worker, "transformed => compressed range '"+output+"'");
        }
        inject.add(output);
        String[] lines = config.split("\n");
        for (int n = 0; n < lines.length; n++) {
            inject.add(lines[n]);
        }
    }


    /**
     * Format data for ned-setting api pretty-line-vty-format
     * @param
     * @return
     * @throws NedException
     */
    @SuppressWarnings("unchecked")
    private String formatPrettyLineVty(NedWorker worker, String data) throws NedException {

        // Extract line vty * / password
        String toptag = "";
        StringBuilder sb = new StringBuilder();
        StringBuilder vtyb = new StringBuilder();
        String[] lines = data.split("\n");
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if (line.isEmpty()) {
                sb.append("\n");
                continue;
            }
            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = line;
            }

            // line vty * / password
            if (toptag.startsWith("line vty ") && line.startsWith(" password ")) {
                vtyb.append(toptag+"\n"+line+"\n!\n");
                continue;
            }

            sb.append(line+"\n");
        }

        data = sb.toString();
        if (vtyb.length() > 0) {
            data += "! \n" + vtyb.toString(); // add "! " to force 2 line vty chunks
        }
        return data;
    }


    /**
     * Replace commit data
     * @param
     * @return
     */
    private String replaceCommitData(NedWorker worker, String data) {
        for (int n = 0; n < replaceCommit.size(); n++) {
            String[] entry = replaceCommit.get(n);
            String regexp = entry[1];
            String replacement = entry[2];
            try {
                Pattern p = Pattern.compile(regexp, Pattern.DOTALL);
                Matcher m = p.matcher(data);
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    traceInfo(worker, "transformed => replaced "+stringQuote(m.group(0))+" with "
                              + matcherToString(m, replacement));
                    m.appendReplacement(sb, replacement); // note: not quoted, want regexp replacements
                }
                m.appendTail(sb);
                data = sb.toString();
            } catch (Exception e) {
                logError(worker, "ERROR in replace-commit '"+entry[0]+"' regexp="+stringQuote(regexp)
                         +" replacement="+stringQuote(replacement), e);
            }
        }
        return data;
    }


    /**
     * Enter config mode
     * @param
     * @throws  NedException, IOException, SSHSessionException
     */
    protected void enterConfig(NedWorker worker, String data) throws NedException, IOException, SSHSessionException {

        if (this.inConfig) {
            traceInfo(worker, "NOTICE: already in config mode");
            return;
        }

        this.useRevert = configRevertTimer > 0 && (data == null || !data.contains("\narchive"));

        // Enter config
        traceVerbose(worker, "entering config mode");
        if (useRevert) {
            session.println("config t revert timer idle "+configRevertTimer);
        } else {
            session.println("config t");
        }

        // Wait for reply
        NedExpectResult res = null;
        for (int n = 1; n < 10; n++) {
            res = session.expect(ENTER_CONFIG_PATTERN, worker);
            switch (res.getHit()) {
            case 0:
                session.println("yes");
                continue;
            case 1:
                sleep(worker, 1000 * n, true);
                // Try enter config again
                if (useRevert) {
                    session.println("config t revert timer idle "+configRevertTimer);
                } else {
                    session.println("config t");
                }
                continue;
            case 2:
            case 3:
                traceVerbose(worker, "entered config mode");
                this.inConfig = true;
                return;
            case 4:
                throw new NedException("turn config archive on before using Rollback Confirmed Change");
            default:
                throw new NedException("failed to enter config mode");
            }
        }

        // Failed to enter config mode
        if (res != null) {
            throw new NedException(res.getText()+expectGetMatch(res));
        } else {
            throw new NedException("failed to enter config mode, timeout");
        }
    }


    /**
     * Exit config mode
     * @param
     * @throws IOException, SSHSessionException
     */
    protected void exitConfig(NedWorker worker, String reason) throws IOException, SSHSessionException {

        traceVerbose(worker, "exiting config mode ("+reason+")");
        if (!inConfig) {
            throw new IOException("Internal error, tried to exit non-config mode");
        }

        for (int i = 1;;i++) {
            NedExpectResult res;
            traceVerbose(worker, "sending 'exit' #"+i);
            session.println("exit");
            res = session.expect(new String[] { "Invalid input detected at", CFG_PROMPT, PROMPT }, worker);
            if (res.getHit() == 0) {
                traceVerbose(worker, "sending 'quit' #"+i);
                session.println("quit");
                res = session.expect(new String[] { "Invalid input detected at", CFG_PROMPT, PROMPT }, worker);
            }
            if (res.getHit() == 2) {
                inConfig = false;
                if (useRevert) {
                    try {
                        print_line_exec(worker, "config confirm");
                    } catch (Exception e) {
                        throw new IOException("failed to confirm config: "+e.getMessage());
                    }
                }
                return;
            }
        }
    }


    /**
     * Send config to device
     * @param
     * @throws Exceptions
     */
    private void sendConfig(NedWorker worker, int cmd, String[] lines)
        throws NedException, IOException, SSHSessionException, ApplyException {

        this.warningsBuf = "";

        // Send commands to device
        modeStack = new ModeStack();
        this.lastTimeout = setReadTimeout(worker);
        String trimmed = "";
        try {
            // Set reboot timer
            setReload(worker);

            // Send one line at a time
            String toptag = "";
            for (int n = 0 ; n < lines.length ; n++) {
                trimmed = lines[n].trim();
                if (trimmed.isEmpty() || trimmed.startsWith(META_DATA)) {
                    continue;
                }

                // Update modeStack
                modeStack.update(lines[n]);

                // Update toptag
                if (isTopExit(lines[n])) {
                    toptag = "";
                } else if (Character.isLetter(lines[n].charAt(0))) {
                    toptag = lines[n];
                }

                //
                // Bulk mode, send chunk of commands before checking replies
                //
                if (chunkSize > 1) {
                    int e;
                    for (e = n; e < lines.length; e++) {
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
                        e = Math.min(e, lines.length);
                    }
                    if (e - n > 1) {
                        sendBulkConfig(worker, lines, n, e);
                        n = e - 1;
                        continue;
                    }
                }

                // Ignore all other comments
                if (trimmed.startsWith("!")) {
                    continue;
                }

                // Text mode
                waitForEcho = Echo.WAIT;
                trimmed = modifyOutputTexts(worker, trimmed);
                if (waitForEcho == Echo.TEXT) {
                    if (trimmed.contains("\t")) {
                        waitForEcho = Echo.DONTWAIT;
                        traceVerbose(worker, "Enabling dontwait");
                    } else {
                        String[] textlines = trimmed.split("\\n");
                        traceVerbose(worker, "Sending '"+textlines[0]+"' -> enabling text mode");
                    }
                }

                // Reset reboot timer
                resetReload(worker);

                // Update timeout each TIMEOUT_MOD
                if ((n % TIMEOUT_MOD) == 0) {
                    lastTimeout = resetReadTimeout(worker, lastTimeout);
                }

                //
                // ned-settings write inject-command * command "exec *"
                //
                if (lines[n].startsWith("exec ")) {
                    traceInfo(worker, "Running inject-command exec command "+stringQuote(lines[n].substring(5)));
                    exitConfig(worker, "exec");
                    print_line_wait_oper(worker, cmd, lines[n].substring(5), 0, writeTimeout);
                    enterConfig(worker, null);
                }

                // Send config line to device
                else {
                    print_line_wait(worker, cmd, trimmed, 0, toptag, n);
                }

            } // for(;;)

        } catch (ApplyException e) {
            boolean hadUseRevert = this.useRevert;
            this.useRevert = false;
            if (this.sentConfigKey && this.oldConfigKey != null) {
                restoreConfigKey(worker, cmd, lines);
            }
            if (e.inConfigMode) {
                exitConfig(worker, "send-exception");
            }
            cancelReload(worker, false);
            if (hadUseRevert) {
                // Use built-in rollback feature to save time in abort() phase
                try {
                    print_line_exec(worker, "config revert now");
                } catch (Exception e2) {
                    // ignore
                }
            }
            traceInfo(worker, "DONE "+nedCmdFullName(cmd)+" - ERROR SENDING: "
                    +stringQuote(e.getMessage())+" "+tickToString(lastTimeout));
            throw e;
        }

        // Cancel optional reboot timer
        cancelReload(worker, true);
    }


    /**
     * Check if line is bulk config
     * @param
     * @return 0 = not bulk, 1 = single line bulk, 2 = bulk mode
     */
    private int isBulkConfig(String line) {
        String cmd = line.startsWith("no ") ? line.substring(3) : line;

        // Non-mode lists
        String[] nonModeLists = {
            "access-list ",
            "ip as-path access-list ",
            "ip community-list ",
            "ip prefix-list ",
            "ip route"
        };
        for (int n = 0; n < nonModeLists.length; n++) {
            if (cmd.startsWith(nonModeLists[0])) {
                return 1;
            }
        }

        // Mode lists
        String[] modeLists = {
            "ip access-list standard ",
            "ip access-list extended ",
            "ip explicit-path ",
            "ipv6 access-list ",
            //disabled due to question on cat9407r "class-map ",
            "policy-map ",
            "route-map "
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
     * Send bulk config
     * @param
     * @throws Exceptions
     */
    private void sendBulkConfig(NedWorker worker, String[] lines, int start, int end)
        throws NedException, IOException, SSHSessionException, ApplyException {
        int n;
        int length = end - start;

        traceInfo(worker, "BULK SENDING "+length+" lines [chunk "+chunkSize+"]");
        traceDev(worker, "   lines.length = "+lines.length+" start = "+start+" end = "+end);

        lastTimeout = setReadTimeout(worker);
        String toptag = "";
        for (int i = start; i < end; i += chunkSize) {

            // Copy in up to chunkSize config commands in chunk
            int num;
            StringBuilder chunk = new StringBuilder();
            for (num = 0, n = i; n < end && n < (i + chunkSize); n++) {
                String line = lines[n];
                if (line == null || line.isEmpty()) {
                    continue;
                }
                String trimmed = line.trim();
                if ("!".equals(trimmed)) {
                    continue;
                }
                chunk.append(line + "\n");
                num++;
            }

            // Send chunk of X lines to device
            traceVerbose(worker, "  BULK SENDING lines "+i+"-"+(i+num-1)+" / "+length);
            session.print(chunk.toString());

            // Check device reply of one line at the time
            for (n = i; n < end && n < (i + chunkSize); n++) {
                String line = lines[n];
                if (line == null || line.isEmpty()) {
                    continue;
                }
                String trimmed = line.trim();
                if ("!".equals(trimmed)) {
                    continue;
                }

                // Update toptag
                if (isTopExit(line)) {
                    toptag = "";
                } else if (Character.isLetter(line.charAt(0))) {
                    toptag = line;
                }

                // Update timeout each TIMEOUT_MOD
                if ((n % TIMEOUT_MOD) == 0) {
                    lastTimeout = resetReadTimeout(worker, lastTimeout);
                }

                // Check device echo and possible input error
                noprint_line_wait(worker, trimmed, toptag);
            }
        }
    }


    /**
     * Set reload timer on device
     * @param
     * @throws ApplyException
     */
    private void setReload(NedWorker worker) throws ApplyException {
        if (applyRebootTimer <= 0 || isNetsim()) {
            return;
        }
        final long start = tick(0);
        this.lastReboot = 0;

        final int minutes = applyRebootTimer;
        String cmd = "do reload in "+minutes;
        traceInfo(worker, "Setting reboot timer to "+minutes+" minutes...");

        // Run the reload command, wait for prompt and first notice
        try {
            // Send reload command and wait for echo
            session.println(cmd);
            session.expect(cmd, worker);

            // Confirm reload
            NedExpectResult res;
            String lastText = "";
            lastTimeout = setReadTimeout(worker);
            boolean retry = true;
            while (retry && minutes > 0) {
                traceVerbose(worker, "Waiting for reload confirmation prompt(s)");
                res = session.expect(new String[] {
                        "\\A.*System configuration has been modified.+\\[[Yy]es/[Nn]o\\]", // 0
                        "\\A.*Proceed with reload\\?[ ]+\\[confirm\\]", // 1
                        "\\A.*unless the configuration register boot bits are non-zero.*",
                        ".*(error|ERROR|Invalid).*"
                    });
                lastText = res.getText();
                traceReceived(worker, res);
                switch (res.getHit()) {
                case 0:
                    traceVerbose(worker, "Sending 'no'");
                    session.println("no");
                    break;
                case 1:
                    traceVerbose(worker, "Confirming with carriage-return + newline");
                    session.print("\r\n");
                    retry = false;
                    break;
                default:
                    traceVerbose(worker, "Swallowing prompt");
                    session.expect(new Pattern[] { Pattern.compile(CONFIG_PROMPT) }, worker);
                    throw new NedException(lastText+expectGetMatch(res));
                }
            }

            // Sync input (due to randomly appearing SHUTDOWN banner with or without prompt)
            traceVerbose(worker, "Syncing prompt");
            cmd = "do show reload";
            session.println(cmd);
            res = session.expect(new Pattern[] { Pattern.compile(cmd) }, worker);
            traceReceived(worker, res);

            traceVerbose(worker, "Waiting for prompt");
            res = session.expect(new Pattern[] { Pattern.compile(CONFIG_PROMPT) }, worker);
            traceReceived(worker, res);

            traceInfo(worker, "Setting reboot timer to "+minutes+" minutes done "+tickToString(start));
            this.lastReboot = tick(0);

        } catch (Exception e) {
            throw new ApplyException(cmd, e.getMessage(), true, true);
        }
    }


    /**
     * Reset reload timer on device
     * @param
     * @throws ApplyException
     */
    private void resetReload(NedWorker worker) throws ApplyException {
        if (applyRebootTimer <= 0) {
            return;
        }
        final long time = tick(0);
        final long diff = time - lastReboot; // in milliseconds
        if (diff > (500 * 60 * applyRebootTimer)) {
            setReload(worker);
        }
    }


    /**
     * Cancel reload timer on device
     * @param
     */
    private void cancelReload(NedWorker worker, boolean configMode) {

        if (applyRebootTimer <= 0 || lastReboot == 0) {
            return;
        }

        final long start = tick(0);
        traceInfo(worker, "Cancelling reboot timer...");

        // Config mode
        String prompt = PRIVEXEC_PROMPT;
        String cmdpfx = "";
        if (configMode) {
            cmdpfx = "do ";
            prompt = CONFIG_PROMPT;
        }

        // Run the reload command, wait for prompt and first notice
        try {
            // Send reload cancel command and wait for echo
            String cmd = cmdpfx + "reload cancel";
            session.println(cmd);
            session.expect(cmd, worker);

            // Confirm reload
            // Note: The SHUTDOWN banner may appear before or after the prompt
            lastTimeout = setReadTimeout(worker);
            traceVerbose(worker, "Waiting for reload 'SHUTDOWN' notice");
            NedExpectResult res = session.expect(new String[] {
                    "SHUTDOWN",
                    "No reload is scheduled" }, worker);
            traceReceived(worker, res);
            if (res.getHit() == 1) {
                traceInfo(worker, "Cancelling reboot timer ignored "+tickToString(start));
                return;
            }

            // Sync input (since some IOS device show prompt after and some do not)
            traceVerbose(worker, "Syncing prompt");
            cmd = cmdpfx + "show reload";
            session.println(cmd);
            res = session.expect(new Pattern[] { Pattern.compile(cmd) }, worker);
            traceReceived(worker, res);

            traceVerbose(worker, "Waiting for prompt");
            res = session.expect(new Pattern[] { Pattern.compile(prompt) }, worker);
            traceReceived(worker, res);

            traceInfo(worker, "Cancelling reboot timer done "+tickToString(start));

        } catch (Exception e) {
            logError(worker, "Cancelling reboot timer ERROR: "+e.getMessage(), e);
        } finally {
            this.lastReboot = 0;
        }
    }


    /**
     * Send a config command line to device, wait for reply and check error
     * @param
     * @throws Exceptions
     */
    private void print_line_wait(NedWorker worker, int cmd, String line, int retrying, String toptag, int num)
        throws NedException, IOException, SSHSessionException, ApplyException {
        final String orgLine = line;
        NedExpectResult res;
        boolean decrypted = false;

        // dirty patch to fix error that happens in timeout
        if ("config t".equals(line)) {
            traceVerbose(worker, "ignored malplaced 'config t'");
            return;
        }

        // Modify tailfned police for testing
        if (line.startsWith("tailfned police ")) {
            iospolice = line.substring(16);
            traceInfo(worker, "SET tailfned police to: "+iospolice);
        }

        // Ignore setting/deleting tailfned|xxyyzztop 'config'
        if (isDevice()) {
            if (line.startsWith("tailfned ") || line.startsWith("no tailfned ")) {
                traceInfo(worker, "ignored tailfned config: " + line);
                return;
            }
            if (line.startsWith("xxyyzztop") || line.startsWith("no xxyyzztop")) {
                traceInfo(worker, "ignored deprecated state variable: " + line);
                return;
            }
        }

        // Ignore setting/deleting cached-show 'config'
        if (line.contains("cached-show ")) {
            traceInfo(worker, "ignored non-config: " + line);
            return;
        }

        // If line contains a Maapi encrypted string then decrypt it and disable tracing
        if (line.contains(" $4$") || line.contains(" $7$") || line.contains(" $8$") || line.contains(" $9$")) {
            String decryptedLine = maapiDecryptLine(worker, line);
            if (!decryptedLine.equals(line)) {
                decrypted = true;
                if (trace) {
                    worker.trace("*" + orgLine + "\n\n", "out", device_id);
                    if (!logVerbose && trace) {
                        session.setTracer(null);
                    }
                }
                line = decryptedLine;
            }
        }

        // Send line (insert CTRL-V before all '?')
        traceDev(worker, "Sending["+nedCmdName(cmd)+num+"]: '"+line+"'");
        session.print(stringInsertCtrlV(line) + "\n");

        // Optional delay, used e.g. to not overload link/device
        if (deviceOutputDelay > 0) {
            sleep(worker, deviceOutputDelay, false);
        }

        // Wait for echo
        if (waitForEcho == Echo.WAIT) {
            if (line.length() > 253 && isDevice()) {
                traceInfo(worker, "Waiting for echo of long line ["+line.trim().length()+" characters]");
                session.expect(new String[] { Pattern.quote(line.substring(0,253)) }, worker);
                session.expect(new String[] { "[\\u0006]*" }, worker);
            } else {
                session.expect(new String[] { Pattern.quote(line) }, worker);
            }
        }

        // Text mode, wait for echo for each line
        else if (waitForEcho == Echo.TEXT) {
            for (String wait: line.split("\n")) {
                res = session.expect(new String[] { Pattern.quote(wait), " Invalid input detected at " }, worker);
                if (res.getHit() == 1) {
                    throw new ApplyException(res.getText(), true, true);
                }
            }
        }

        // Enable tracing if disabled due to sending decrypted clear text passwords
        if (decrypted) {
            if (trace) {
                session.setTracer(worker);
                worker.trace("*" + orgLine + "\n", "out", device_id);  // simulated echo
            }
            line = orgLine;
        }

        //
        // Wait for prompt, allow a maximum of 3 loops
        //
        res = null;
        for (int loops = 0; loops < 3; loops++) {
            res = session.expect(plw, worker);
            if (waitForEcho != Echo.WAIT || res.getHit() < 4) {
                break;
            }

            // Check for a blocking confirmation prompt
            traceVerbose(worker, "PROMPT text="+stringQuote(res.getText())+" match="+stringQuote(expectGetMatch(res)));

            // Matched write/inject-answer
            if (res.getHit() >= PLW0.length) {
                // First check all entries, matching optional ml-question
                String[] entry = null;
                for (int n = 0; n < injectAnswer.size(); n++) {
                    entry = injectAnswer.get(n);
                    if (entry[3] == null) {
                        continue;
                    }
                    Pattern p = Pattern.compile(entry[3], Pattern.DOTALL);
                    Matcher m = p.matcher(res.getText());
                    if (m.find()) {
                        break;
                    }
                    entry = null;
                }
                if (entry == null) {
                    entry = injectAnswer.get(res.getHit() - PLW0.length);
                }
                traceInfo(worker, "Matched write/inject-answer "+entry[0]+": injecting answer "+stringQuote(entry[2]));
                session.print(entry[2]);
                // Note: do not wait for echo, can be passwords which are not echoed
            }

             // 4 - ? (y/n[)
            else if (res.getHit() == 4) {
                traceInfo(worker, "Confirming question, sending newline");
                session.println("");
            }

            // 10 - 'Old key: '
            else if (res.getHit() == 10) {
                this.sentConfigKey = true;
                if (this.oldConfigKey == null) {
                    traceInfo(worker, "Sending invalid config-key password");
                    session.println("an-invalid-config-key");
                } else {
                    final String logpw = devTraceEnable ? this.oldConfigKey : "<HIDDEN>";
                    traceInfo(worker, "Sending old config-key password\n"+logpw);
                    if (trace) {
                        session.setTracer(null);
                    }
                    session.println(this.oldConfigKey);
                    if (trace) {
                        session.setTracer(worker);
                    }
                }
            }

            // 11 - '%% Invalid key entered' (received when sent bad old config-key password)
            else if (res.getHit() == 11) {
                if (line.startsWith("key config-key password-encrypt ")) {
                    traceInfo(worker, "Invalid old config-key, deleting it to allow reset");
                    session.println("no key config-key password-encrypt");
                    session.expect(new String[] { "no key config-key password-encrypt" }, worker);
                    res = session.expect(new String[] { "Continue with master key deletion", CFG_PROMPT }, worker);
                    if (res.getHit() == 0) {
                        session.println("yes");
                    }
                    // Re-send the original key config-key line
                    traceInfo(worker, "Re-sending["+nedCmdName(cmd)+num+"]: '"+line+"'");
                    if (trace && !devTraceEnable) {
                        session.setTracer(null);
                    }
                    final String decryptedLine = maapiDecryptLine(worker, line);
                    session.println(decryptedLine);
                    res = session.expect(new String[] { Pattern.quote(decryptedLine) }, worker);
                    if (trace && !devTraceEnable) {
                        session.setTracer(worker);
                    }
                    // continue in for-loop, waiting for device config prompt
                }
            }

            // 5 - 9 - Standard YES and NO questions
            else {
                // First try sending a 'y' only, wait 1 sec for prompt
                session.print("y");
                session.expect(new String[] { "y" }, worker);
                try {
                    res = session.expect(plw, false, 1000, worker);
                    break;
                } catch (Exception e) {
                    // Timeout -> send 'es\n' for a full 'yes' + enter
                    session.println("es");
                    session.expect(new String[] { "es" }, worker);
                }
            }
        }

        // Get reply text (note: after confirm-questions for new text)
        final String reply = res.getText();
        final String prevPrompt = lastPrompt;
        lastPrompt = expectGetMatch(res);
        traceDev(worker, "Received: text="+stringQuote(reply)+" match="+stringQuote(lastPrompt));

        // Dirty fix for mtu command leaving sub-mode (e.g. xconnect)
        if (line.startsWith("mtu ") && !lastPrompt.equals(prevPrompt)) {
            throw new ApplyException(line, "failed, left sub-mode", true, true);
        }

        // Check prompt
        switch (res.getHit()) {
        case 0:
        case 1:
        case 2:
            // config mode
            break;
        case 3:
            if (lastPrompt.startsWith("##########")) {
                break; // ugly command banner patch (for e.g. no license solution level)
            }
            // exec mode
            inConfig = false;
            traceInfo(worker, "SENDING ERROR: command '"+line+"' caused exit from config mode");
            throw new ApplyException(line, "exited from config mode", true, false);
        default:
            exitPrompting(worker);
            traceInfo(worker, "SENDING ERROR: command '"+line+"' prompted twice");
            throw new ApplyException(line, "Internal ERROR: prompted twice", true, true);
        }

        // Look for retries
        final int maxRetries = isCliRetry(line, reply);
        if (maxRetries > 0) {
            // Wait a while and retry
            if (retrying >= maxRetries) {
                // Already tried enough, give up
                throw new ApplyException(line, "["+retrying+" retries]: "+reply, true, true);
            }
            else {
                // Sleep (default 1000ms), reset timeout(s) and try same command again
                sleep(worker, configOutputRetryInterval, true);
                setReadTimeout(worker);
                resetReload(worker);
                traceVerbose(worker, "Retry #" + (retrying+1));
                print_line_wait(worker, cmd, line, retrying+1, toptag, num);
                return;
            }
        }

        // Look for errors
        if (waitForEcho == Echo.WAIT && isCliError(worker, cmd, reply, line, toptag)) {
            throw new ApplyException(line+"\r\n", reply.trim()+modeStack.toString(), true, true);
        }

        // Retry succeeded, reset timeout
        if (retrying > 0) {
            traceInfo(worker, "Retry success after " + retrying + " retries");
            setReadTimeout(worker);
        }

        // Sleep three seconds for clear command to take effect (RT20042)
        if ("do clear crypto ikev2 sa fast".equals(line) || line.startsWith("do clear ip nat ")) {
            resetTimeout(worker, this.readTimeout + 3000, 0);
            sleep(worker, 3000, true); // Sleep 3 seconds
        }
    }


    /**
     * Wait for device reply and verify it
     * @param
     * @throws Exceptions
     */
    private void noprint_line_wait(NedWorker worker, String trimmed, String toptag)
        throws NedException, IOException, SSHSessionException, ApplyException {

        // Wait for echo
        session.expect(new String[] { Pattern.quote(trimmed) }, worker);

        // Second, wait for the prompt
        NedExpectResult res = session.expect(plw, worker);

        // Third, check if we exited config mode
        switch (res.getHit()) {
        case 0: // (cfg) - top mode
        case 1: // (config) - top mode
        case 2: // (.*) - sub-mode
            break;
        case 3: // exec mode
            inConfig = false;
            throw new ApplyException(trimmed, "exited from config mode", true, false);
        default:
            throw new ApplyException(trimmed, "Internal ERROR: device prompted", true, true);
        }

        // Verify no retry
        String reply = res.getText();
        if (isCliRetry(trimmed, reply) > 0) {
            throw new ApplyException(trimmed, "Internal ERROR: retry-command", true, true);
        }

        // Check for device error
        if (isCliError(worker, NedCmd.PREPARE_CLI, reply, trimmed, toptag)) {
            throw new ApplyException(trimmed+"\r\n", reply.trim()+modeStack.toString(), true, true);
        }
    }


    /**
     * Check if command must be retried
     * @param
     * @return max number of retries if retry command, else 0
     */
    private int isCliRetry(String trimmed, String reply) {

        if (reply.trim().isEmpty()) {
            return 0;
        }

        // Ignore retry on these patterns:
        final String[] ignoreRetry = {
            "%(\\S+): (informational|error): \\S+ is in use on",
            "please remove .* from .* first",
            "is in use[.] remove from .* before deleting", // no flow monitor *
            "is in use[.] cannot be deleted", // no policy-map *
            "profile is in use, the update will overwrite", // cable profile *
            "first remove .* from the above", // no crypto ipsec transform-set
            "\\S+ is in use and cannot be modify or delete",
            "failed to field add[:] object is in use",
            "failed to set .+ .+ is in use. remove from all interfaces"
        };
        for (int n = 0; n < ignoreRetry.length; n++) {
            if (findString(ignoreRetry[n], reply.toLowerCase()) >= 0) {
                return 0;
            }
        }

        // Short retry on these:
        final String[] isShort = {
            "flow \\S+ is in use. remove from all \\S+ before (editing|modification)"
        };
        for (int n = 0; n < isShort.length; n++) {
            if (findString(isShort[n], reply.toLowerCase()) >= 0) {
                return 3;
            }
        }
        if (reply.contains("Invalid input detected at ")
            && (trimmed.startsWith("duplex ") || trimmed.startsWith("speed 1"))) {
            // interface speed|duplex command may not be available until some delay after "no shutdown" [CISCOIOS-2178]
            return 3;
        }

        // Retry on these patterns:
        final String[] isRetry = {
            "is in use",
            "is still in use and cannot be removed",
            "wait for it to complete",
            "wait for the current operation to complete",
            "wait for the operation to complete before making this change",
            "wait for current config download to complete",
            "Config update in progress; please wait and retry",
            "Please wait \\d+ seconds for the cleanup to complete before attempting",
            "is currently being deconfigured",
            "is currently deactivating",
            "is being deleted, please try later",
            "is being deleted.* Try it later",
            "being configured in another session.* try again later",
            "are down, try again later",
            "Certificate server is busy, initial .* unable to be processed, try again later",
            "Configuration rejected due to previous .+ configuration change is in process, please try later",
            "In-use PW template cannot be removed", // no template type pseudowire
            " already in use by VRF", // vrf definition * / rd
            "You may retry shortly", // iox
            "VTP feature not yet initialized", // vlan 100
            "Controller \\S+ failed to bind profile" // rpd-ds 1 downstream-cable 2/0/6 profile
        };
        for (int n = 0; n < isRetry.length; n++) {
            if (findString(isRetry[n], reply) >= 0) {
                return configOutputMaxRetries;
            }
        }

        // Do not retry
        return 0;
    }


    /**
     * Check if device reply is an error or if the output should be ignored
     * @param
     * @return
     */
    private boolean isCliError(NedWorker worker, int cmd, String reply, String line, String toptag) {

        // Debug code:
        if (cmd == NedCmd.ABORT_CLI && failphase.contains("abort")) {
            traceInfo(worker, "DEBUG: Simulating failed abort");
            failphase = failphase.replace("abort", "");
            return true;
        }

        // Strip shutdown info message(s)
        reply = reply.replaceAll("\\*\\*\\*\r\n\\*\\*\\* --- SHUTDOWN in \\S+ ---\r\n\\*\\*\\*\r\n", "");
        String replyall = reply;

        // Trim and check if empty reply
        reply = reply.replaceAll("\\r", "").trim();
        if (reply.isEmpty() || reply.length() <= 1) {
            return false;
        }

        // Strip echo of the failing command 'line'
        if (reply.contains("Invalid input")) {
            reply = reply.replace(line, "");
        }

        // Check all warnings, may be multiple
        reply = "\n" + reply;
        String[] warnings = reply.split("\n% ");
        for (int i = 0; i < warnings.length; i++) {
            String warning = warnings[i].trim();
            if (warning.isEmpty() || warning.length() <= 1) {
                continue;
            }
            if (isCliError2(worker, cmd, replyall, warning, line, toptag)) {
                return true;
            }
        }
        return false;
    }


    /**
     * Do'er method for isCliError
     * @param
     * @return
     */
    private boolean isCliError2(NedWorker worker, int cmd, String replyall, String reply, String line, String toptag) {

        reply = reply.trim();
        if (reply.isEmpty()) {
            return false;
        }

        if (reply.equals(line)) {
            traceInfo(worker, "Ignoring command echo '"+reply+"'");
            return false;
        }

        traceVerbose(worker, "Checking device reply "+stringQuote(reply));

        //
        // Special cases:
        //

        // mpls traffic-eng lsp attributes * / *
        if (toptag.startsWith("mpls traffic-eng lsp attributes ") && !isExecError(reply)) {
            traceInfo(worker, "Ignoring command echo '"+reply+"'");
            return false;
        }
        if (line.startsWith("default ")) {
            traceInfo(worker, "Ignoring output from default command");
            return false;
        }
        if ("snmp-server enable traps".equals(line)) {
            traceInfo(worker, "Ignoring output from macro");
            return false;
        }
        if ("no shutdown".equals(line) && reply.contains("shutdown can't be applied on standby interface")) {
            // Happens if interface used as "backup interface"
            return false;
        }
        if (line.contains("no ip address ") && reply.contains("Invalid address")) {
            // Happens when IP addresses already deleted on interface
            return false;
        }
        if (("no duplex".equals(line) || "no speed".equals(line) || "speed auto".equals(line))
            && !reply.contains("Auto-negotiation is enabled. Speed cannot be set")) {
            // Ignore these errors because harmless and happen:
            // E.g. when 'no media-type' is deleted before duplex or speed
            // E.g. when 'no speed' is sent after negotiation auto
            // E.g. when no speed & speed auto are both sent
            traceInfo(worker, "Ignoring error/warning");
            return false;
        }
        if ("no mpls control-word".equals(line)) {
            traceInfo(worker, "Ignoring '"+line+"' (cli-show-no)");
            return false;
        }
        if (line.contains("switchport") && reply.contains("Maximum number of interfaces reached")) {
            return true;
        }
        if ("no switchport".equals(line)) {
            // Can't do no switchport on some devices:
            traceInfo(worker, "Ignoring non-required command");
            return false;
        }
        if ("switchport".equals(line) && reply.contains("Incomplete command")) {
            // Some devices (e.g. 891) do not use switchport on single line.
            // Some devices do not support switchport on Port-channel if.
            traceInfo(worker, "Ignoring non-required command");
            return false;
        }
        if (line.startsWith("ip redirects")
            && replyall.contains("ip redirect is not applicable for p2p link")) {
            traceInfo(worker, "Ignoring non-required command");
            return false;
        }
        if (line.startsWith("no interface ")
            && replyall.contains("Sub-interfaces are not allowed on switchports")) {
            traceInfo(worker, "Ignoring useless warning");
            return false;
        }
        if ((line.startsWith("no interface Integrated-Cable") || line.startsWith("no interface Wideband-Cable"))
            && replyall.contains("Removal of physical interfaces is not permitted")) {
            // Delete of e.g. Integrated-Cable6/0/0:158 deleted by shutdown first [CISCOIOS-2274,CISCOIOS-2330,CISCOIOS-2352]
            traceInfo(worker, "Ignoring delete warning");
            return false;
        }

        if (line.startsWith("no cem ") &&
            ((reply.contains("Circuit ") && reply.contains(" not present"))
             || reply.contains("Please use no cem-group command under controller to remove"))) {
            traceInfo(worker, "Ignoring non-required command");
            return false;
        }

        //
        // 'Invalid input detected at'
        //
        if (reply.contains("Invalid input detected at")) {

            // Specific config lines:
            if (line.contains("reporting smart-licensing-data")) {
                traceInfo(worker, "Ignoring non-required command");
                return false;
            }
            if (line.startsWith("no interface LISP") || line.startsWith("no interface CEM")) {
                // Delete of router lisp deletes LISP interfaces (which in turn can't be deleted first)
                // Delete of controller E1 * / framing deletes the CEM interface (which in turn can't be deleted first)
                traceInfo(worker, "Ignoring delete of missing interface");
                return false;
            }
            // router lisp / ipv4 map-cache-persistent
            if (line.startsWith("no ") && line.contains(" map-cache-persistent")) {
                traceInfo(worker, "Suppressed delete invalid error on: " + line);
                return false;
            }
            // cable service class * downstream
            // cable service class * upstream
            if (line.startsWith("no cable service class ") && line.endsWith("stream")) {
                traceInfo(worker, "Suppressed delete invalid error on: " + line);
                return false;
            }
            // redundancy / notification-timer
            if (line.startsWith(" no notification-timer ")) {
                traceInfo(worker, "Suppressed delete invalid error on: " + line);
                return false;
            }

            // Ignore Invalid input error on non-existing injected config
            for (int n = interfaceConfig.size()-1; n >= 0; n--) {
                String[] entry = interfaceConfig.get(n);
                if (findString(line, entry[1]) >= 0) {
                    traceInfo(worker, "Ignoring non-supported injected interface config");
                    return false;
                }
            }
            for (int n = injectConfig.size()-1; n >= 0; n--) {
                String[] entry = injectConfig.get(n);
                if (findString(line, entry[2]) >= 0) {
                    traceInfo(worker, "Ignoring non-supported injected config '"+entry[2]+"'");
                    return false;
                }
            }
        }

        //
        // Static error triggers (overriding ignore)
        //
        for (int n = 0; n < staticError.length; n++) {
            if (findString(staticError[n], reply) >= 0) {
                traceInfo(worker, "ERROR SENDING - matched static error '"+reply+"'");
                return true;
            }
        }

        //
        // Ignore static warnings
        //
        for (int n = 0; n < staticWarning.length; n++) {
            if (findString(staticWarning[n], reply.toLowerCase()) >= 0) {
                traceInfo(worker, "ignoring static warning: "+stringQuote(staticWarning[n]));
                warningsBuf += "> "+line+"\n"+reply+"\n";
                return false;
            }
        }

        //
        // Ignore static no-warnings
        //
        if (line.startsWith("no ")) {
            for (int n = 0; n < staticNoWarning.length; n++) {
                if (findString(staticNoWarning[n], reply.toLowerCase()) >= 0) {
                    traceInfo(worker, "ignoring static no-warning: "+stringQuote(staticNoWarning[n]));
                    warningsBuf += "> "+line+"\n"+reply+"\n";
                    return false;
                }
            }
        }

        //
        // Ignore dynamic warnings
        //
        for (int n = 0; n < dynamicWarning.size(); n++) {
            if (findString(dynamicWarning.get(n), reply) >= 0) {
                traceInfo(worker, "ignoring dynamic warning: '"+reply+"'");
                warningsBuf += "> "+line+"\n"+reply+"\n" ;
                return false;
            }
        }

        //
        // Ignore all errors when rollbacking due to abort (i.e. a previous error)
        //
        if (writeIgnoreAbortErrors && cmd == NedCmd.ABORT_CLI) {
            traceInfo(worker, "ignoring ABORT error: "+stringQuote(reply));
            return false;
        }

        // Fail on all else
        traceInfo(worker, "ERROR SENDING - reply '"+reply+"'");
        return true;
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
        final long start = tick(0);
        if (trace) {
            session.setTracer(worker);
        }
        traceInfo(worker, "BEGIN PERSIST");

        // Save config
        if (!ignoreNextWrite && "on-persist".equals(this.writeMemoryMode)) {
            saveConfig(worker, NedCmd.PERSIST);
        }

        traceInfo(worker, "DONE PERSIST "+tickToString(start));
        worker.persistResponse();
    }


    /**
     * Save configuration on device
     * @param
     * @throws NedException, IOException, SSHSessionException, ApplyException
     */
    private void saveConfig(NedWorker worker, int cmd)
        throws NedException, IOException, SSHSessionException, ApplyException {

        // Reconnect to device if remote end closed connection due to being idle
        if (session.serverSideClosed()) {
            reconnectDevice(worker);
        }

        // Save running-config to startup-config
        print_line_wait_oper(worker, cmd, this.writeMemory, 0, writeTimeout);
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

        if (this.ignoreNextWrite) {
            this.ignoreNextWrite = false;
        } else {
            // Save config
            if ("on-commit".equals(this.writeMemoryMode)) {
                saveConfig(worker, NedCmd.COMMIT);
            }

            // Archive config
            configArchive.archive(worker);
        }

        traceInfo(worker, "DONE COMMIT "+tickToString(start));
        worker.commitResponse();
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
        traceInfo(worker, "BEGIN ABORT");

        if (relock.length() > 0) {
            String relockBuf = relock.toString();
            relock.setLength(0);
            traceInfo(worker, "locks: pre-injecting relock(s) "+stringQuote(relockBuf));
            data = relockBuf + data;
        }

        // Apply the abort
        doApplyConfig(worker, NedCmd.ABORT_CLI , data);

        traceInfo(worker, "DONE ABORT "+tickToString(start));
        worker.abortResponse();
    }


    /*
     **************************************************************************
     * revert
     **************************************************************************
     */

    /**
     * Revert config
     * @param
     * @throws Exception
     */
    @Override
    public void revert(NedWorker worker, String data) throws Exception {
        final long start = tick(0);
        if (trace) {
            session.setTracer(worker);
        }
        traceInfo(worker, "BEGIN REVERT");

        // Apply the revert
        doApplyConfig(worker, NedCmd.REVERT_CLI, data);

        // Save config
        if ("on-commit".equals(this.writeMemoryMode)) {
            saveConfig(worker, NedCmd.REVERT_CLI);
        }

        // Archive config
        configArchive.archive(worker);

        traceInfo(worker, "DONE REVERT "+tickToString(start));
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
        this.totalTraces = 0;
        this.totalLines = 0;
        this.totalTime = 0;
        this.totalFailed = 0;

        if (trace) {
            session.setTracer(worker);
        }

        // Prepare command
        String cmd = nedCommand.prepare(worker, cmdName, p);

        // internal - show warnings
        StringBuilder reply = new StringBuilder();
        if ("show warnings".equals(cmd)) {
            reply.append("\nWarnings/output since last commit: \n"+ warningsBuf);
        }

        // internal - show ned-settings
        else if ("show ned-settings".equals(cmd)) {
            reply.append("\n"+nedSettings.dumpAll());
        }

        // internal - show outformat raw
        else if ("show outformat raw".equals(cmd)) {
            reply.append("\nNext dry-run will show raw (unmodified) format.\n");
            this.showRaw = true;
        }

        // internal - set iosmodel
        else if (cmd.startsWith("set iosmodel ")) {
            iosmodel = cmd.substring(13);
            reply.append("\niosmodel set to '"+iosmodel+"'");
        }

        // internal - secrets resync
        else if ("secrets resync".equals(cmd)) {
            secrets.setResync(true);
            // FIXME: does not trigger callbacks?
            modifyInput(worker, getConfig(worker));
            secrets.setResync(false);
            reply.append("\nRe-synced all cached secrets.\n");
        }

        // internal - secrets delete
        else if ("secrets delete".equals(cmd)) {
            secrets.delete(worker);
            reply.append("\nDeleted cached secrets.\n");
        }

        // internal - sync-from-file <file|trace>
        else if (cmd.startsWith("sync-from-file ")) {
            this.syncFile = cmd.trim().substring(15).trim();
            reply.append("\nNext sync-from will use file = " + this.syncFile + "\n");
        }

        // internal - check-config-trace <trace file>
        else if (cmd.startsWith("check-config-trace ")) {
            final File file = new File(cmd.trim().substring(19).trim());
            int unknown = intCmdCheckConfigTrace(worker, file, reply, null);
            if (unknown == 0) {
                reply.append("\nNo unsupported config in "+cmd.trim().substring(19).trim()+"\n");
            }
        }

        // internal - check-config-dir <directory>
        else if (cmd.startsWith("check-config-dir ")) {
            intCmdCheckConfigDir(worker, cmd, reply, false);
        }

        // internal - check-config-all <directory>
        else if (cmd.startsWith("check-config-all ")) {
            intCmdCheckConfigDir(worker, cmd, reply, true);
        }

        // internal - config-if <cmd> [:: <regex>]
        else if (cmd.startsWith("config-if")) {
            intCmdConfigIf(worker, cmd, reply);
        }

        // internal - maapi-decrypt <password>
        else if (logVerbose && devTraceEnable && cmd.startsWith("maapi-decrypt ")) {
            String password = cmd.substring(14).trim();
            try {
                password = mCrypto.decrypt(password);
                reply.append("\ndecrypted password = '"+password+"'\n");
            } catch (Exception e) {
                reply.append("failed to maapi-decrypt '"+password+"'");
            }
        }

        // internal - fail <phase>
        else if (cmd.startsWith("fail ")) {
            failphase = cmd.substring(5).trim();
            reply.append("\nfailphase set to: '"+failphase+"'\n");
        }

        // internal - accept-eula
        else if (cmd.startsWith("accept-eula")) {
            int wait = Integer.parseInt(cmd.substring(12).trim());
            if (wait > 0) {
                traceInfo(worker, "Waiting for EULA agreement banner and prompt for "+wait+" seconds");
                try {
                    Pattern[] cmdPrompt = new Pattern[] {
                        Pattern.compile(".*ACCEPT[ ]+\\[y/n\\]")
                    };
                    NedExpectResult res = session.expect(cmdPrompt, true, wait * 1000, worker);
                    if (res.getHit() == 0) {
                        traceInfo(worker, "Sending 'y', accepting EULA agreement");
                        session.println("y");
                        // Wait for prompt?
                        reply.append("\nAccepted EULA agreement\n");
                    }
                } catch (Exception e) {
                    reply.append("\nTimeout with no EULA agreement prompt\n");
                }
            }
        }

        // internal - help|usage
        else if (cmd.startsWith("help") || cmd.startsWith("usage")) {
        }

        // Execute command on device
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
            reply.append("  accept-eula <seconds to wait> - accept EULA agreement on device\n");
            reply.append("  check-config-trace <trace> - Check config from first show run in trace\n");
            reply.append("  check-config-dir <path> - Check all traces in a directory\n");
            reply.append("  check-config-all <path> - Concatenate all traces config and check\n");
            reply.append("  config-if <cmd> [:: <regex>] - Run config command(s) on interface(s)\n");
            reply.append("  fail <phase> - internal test command to set fail phase\n");
            reply.append("  secrets resync - re-sync all cached secrets\n");
            reply.append("  secrets delete - delete all cached secrets\n");
            reply.append("  set iosmodel <model> - Temporarily change IOS model\n");
            reply.append("  show ned-setting - show ned-settings\n");
            reply.append("  show outformat raw - Next commit dry-run will show raw format\n");
            reply.append("  sync-from-file <text file> - Sync config from file <text file>>\n");
        }

        // Internal command reply
        traceInfo(worker, "COMMAND - internal: "+stringQuote(cmd));
        traceInfo(worker, reply.toString());
        worker.commandResponse(new ConfXMLParam[] { new ConfXMLParamValue("ios-stats", "result",
                                                                          new ConfBuf(reply.toString()))});
    }


    /**
     * Parse all .trace files in directory and return unknown config lines
     * @param
     * @throws Exception
     */
    private void intCmdCheckConfigDir(NedWorker worker, String cmd, StringBuilder reply, boolean unique)
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
                int unknown = intCmdCheckConfigTrace(worker, files[i], reply, uniqueSb);
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
            reply.append("\nIgnored "+this.totalFailed+" trace(s) from unknown NED, NETSIM or missing configuration.");
        }
        writeFile(reply.toString(), dir+"/cisco-ios-traces-config.txt");
    }


    /**
     * Parse a trace file and store unknown config lines in StringBuilder
     * @param
     * @throws Exception
     */
    private int intCmdCheckConfigTrace(NedWorker worker, File file, StringBuilder reply, StringBuilder uniqueSb)
        throws Exception {
        String model = UNKNOWN;
        String version = UNKNOWN;

        final String name = file.getName();
        traceInfo(worker, "+++ Reading trace file: "+name);
        StringBuilder sb = new StringBuilder();
        Scanner myReader = new Scanner(file);
        try {
            while (myReader.hasNextLine()) {
                String line = myReader.nextLine();
                if (line.contains("- NED VERSION: ") && !line.contains(" cisco-ios ")) {
                    traceInfo(worker, "    Ignoring non cisco-ios NED trace: "+name);
                    this.totalFailed++;
                    return 0;
                }
                if (line.startsWith("Cisco IOS Software, NETSIM")) {
                    traceInfo(worker, "    Ignoring NETSIM trace: "+name);
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
        if (sb.indexOf("\nCurrent configuration") < 0) {
            reply.append("\n+++ "+name+":\n  Model: "+model+" Version: "
                         +version+"\n  INFO: Missing 'Current configuration'");
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

            end = dump.indexOf("\nend");
            if (end > 0) {
                dump = dump.substring(0, end+5);
            }
            break;
        }

        // Trim and modify input config
        final String orgModel = this.iosmodel;
        final String orgPolice = this.iospolice;
        try {
            this.offlineData = "COMMAND";
            this.iosmodel = model;
            this.iospolice = getIosPolice(worker, -1, false);

            this.totalTraces++;

            // Modify input config
            dump = trimInput(worker, dump);
            String config = modifyInput(worker, dump);
            config = config.replaceAll("tailfned police .*", "tailfned police " + this.iospolice);

            traceDev(worker, "SHOW-RUN-AFTER=\n+++ begin\n"+config+"\n+++ end");

            // Append or verify config with turbo-parser
            if (uniqueSb != null) {
                uniqueSb.append("! PARSER_EXIT_TO_TOP\n");
                uniqueSb.append(config);
                return 0;
            } else {
                traceInfo(worker, "+++ Parsing trace file: "+name+" model="+iosmodel+" police="+iospolice);
                StringBuilder one = new StringBuilder();
                int unknown = parseConfig(worker, config, one, false);
                if (unknown > 0) {
                    reply.append("\n+++ "+name+":\n  Model: "+model+" Version: "+version+"\n");
                    reply.append(one);
                } else {
                    traceInfo(worker, "+++ 0 unknown in "+name+":\n  Model: "+model+" Version: "+version+"\n");
                }
                return unknown;
            }

        } finally {
            this.iosmodel = orgModel;
            this.iospolice = orgPolice;
            this.offlineData = null;
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
        try {
            pctx = newParserContext(worker,Arrays.asList(lines),Schema.ParserDirection.FROM_DEVICE);
            pctx.parse();
        } catch (Exception e) {
            final String log = "turbo-parser ERROR: "+e.getMessage();
            reply.append(log+"\n");
            logError(worker, log, e);
            traceInfo(worker, "loaded-config:\n"+config);
            return 1; /// unknown unknown, return 1
        }

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
     * internal command 'config-if' - Execute command on all interfaces on device
     * @param
     * @throws Exception
     */
    private void intCmdConfigIf(NedWorker worker, String cmd, StringBuilder reply) throws Exception {

        // List interfaces
        String show = nedCommand.runCommand(worker, "show interfaces | i line protocol");
        String[] lines = show.trim().split("\n");
        if ("config-if".equals(cmd.trim())) {
            reply.append(this.device_id+" interfaces:\n");
            for (int i = 0; i < lines.length; i++) {
                String[] words = lines[i].trim().split(" +");
                if (words[0].isEmpty() || words[0].contains("#")) {
                    continue;
                }
                reply.append(words[0]+"\n");
            }
            return;
        }

        // Command with optional regex ':: <regex>'
        String[] opts = cmd.trim().substring(10).split(" :: ");
        String[] cmds = opts[0].split(" ; ");

        // Enter config mode
        enterConfig(worker, null);

        // Run command on each interface (optionally matching regex)
        try {
            reply.append("\n");
            for (int i = 0; i < lines.length; i++) {
                String[] words = lines[i].trim().split(" +");
                if (words[0].isEmpty() || words[0].contains("#")) {
                    continue;
                }
                if (opts.length > 1) {
                    Pattern p = Pattern.compile(opts[1]);
                    Matcher m = p.matcher(words[0]);
                    if (!m.find()) {
                        continue;
                    }
                }
                reply.append("\n+++\n> interface "+words[0]+" / "+opts[0]);
                String output = nedCommand.runCommand(worker, "interface "+words[0]);
                reply.append(output);
                for (int c = 0; c < cmds.length; c++) {
                    output = nedCommand.runCommand(worker, cmds[c]);
                    reply.append(output);
                }

                // Do not exit interface (in case the command failed and did that by itself)
                reply.append("\n");
            }
        } finally {
            // Exit config mode
            if (this.inConfig) {
                exitConfig(worker, "internal cmd config-if");
            }
        }
    }


    /**
     * Exit prompting
     * @param
     * @throws Exceptions
     */
    protected void exitPrompting(NedWorker worker) throws IOException, SSHSessionException {

        Pattern[] cmdPrompt = new Pattern[] {
            // Prompt patterns:
            Pattern.compile(PRIVEXEC_PROMPT),
            Pattern.compile(CFG_PROMPT),
            Pattern.compile(ANY_PROMPT),
            // Question patterns:
            Pattern.compile(":\\s*$"),
            Pattern.compile("\\]\\s*$")
        };

        while (true) {
            traceVerbose(worker, "Sending CTRL-C");
            session.print("\u0003");
            traceVerbose(worker, "Waiting for non-question");
            NedExpectResult res = session.expect(cmdPrompt, true, readTimeout, worker);
            if (res.getHit() <= 2) {
                traceVerbose(worker, "Got prompt ("+res.getHit()+")");
                return;
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
        NedWorker worker = getWorker();
        if (session == null) {
            traceInfo(null, "IS-ALIVE = false (null session)");
            return false;
        }
        if (devFailIsAlive > 0 && --devFailIsAlive == 0) {
            traceInfo(worker, "IS-ALIVE = false (ned-setting simulated)");
            return false;
        }
        boolean alive = !session.serverSideClosed();
        traceInfo(worker, "IS-ALIVE = "+alive);
        return alive;
    }


    /*
     **************************************************************************
     * didReconnect
     **************************************************************************
     */

    /**
     * Called when NSO reconnects to NED
     * @param
     * @throws Exception
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
    @Override
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
                session.expect(new String[] { CONFIG_PROMPT, PRIVEXEC_PROMPT}, worker);
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
     * @throws NedException
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
     * cleanup
     **************************************************************************
     */

    /**
     * Clear cached data, called by close() methods
     * @param
     */
    @Override
    protected void cleanup() {
        traceInfo(getWorker(), "CLEANUP - cleaning cached data");
        clearDataCache();
    }


    /*
     **************************************************************************
     * NedSecretCliExt
     **************************************************************************
     */

    /**
     * Used by NedSecretCliExt to check whether a secret is cleartext or encrypted.
     * Method must be implemented by all NED's which use NedSecretCliExt.
     * @param secret - The secret
     * @return True if secret is cleartext, else false
     */
    @Override
    public boolean isClearText(String secret) {
        String trimmed = secret.trim();

        // encrypted
        if (secret.matches("[0-9a-f]{2}(:([0-9a-f]){2})+")) {
            return false;  // aa:11 .. :22:bb
        }
        if (trimmed.startsWith("encrypted ")) {
            return false;  // encrypted XXX
        }
        if (trimmed.contains(" encrypted")) {
            return false;  // XXX encrypted
        }
        if (trimmed.startsWith("password ")) {
            return false;  // password XXX
        }
        if (trimmed.endsWith(" 7")) {
            return false;  // XXX 7
        }
        if (getMatch(trimmed, "^([1-9] \\S+)") != null) {
            return false;  // [1-9] XXX
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
     * Trace expect buffers
     * @param
     */
    private void traceReceived(NedWorker worker, NedExpectResult res) {
        traceDev(worker, "Received: text="+stringQuote(res.getText())
                    +" match="+stringQuote(expectGetMatch(res)));
    }


    /**
     * Return number of spaces " " before a CLI commands
     * @param
     * @return
     */
    private String spaces(String cmd) {
        return cmd.replace(cmd.trim(),"");
    }


    /**
     * Strip path for cleaner output
     */
    protected String shortpath(String path) {
        int i = path.indexOf("/config/");
        if (i < 0) {
            return path;
        }
        path = path.substring(path.indexOf("/config/")+8);
        return path.replaceFirst("ios:", "");
    }


    /**
     * Attach to Maapi
     * @param
     * @throws NedException
     */
    private void maapiAttach(NedWorker worker, int fromTh, int toTh) throws NedException {
        try {
            int usid = worker.getUsid();
            traceDev(worker, "Maapi.Attach: from="+fromTh+" to="+toTh+" usid="+usid);
            if (fromTh != -1) {
                maapi.attach(fromTh, 0, usid);
            }
            if (toTh != -1) {
                maapi.attach(toTh, 0, usid);
            }
        } catch (Exception e) {
            throw new NedException("Internal ERROR: maapiAttach(): "+e.getMessage(), e);
        }
    }


    /**
     * Detach from Maapi
     * @param
     */
    private void maapiDetach(NedWorker worker, int fromTh, int toTh) {
        try {
            traceDev(worker, "Maapi.Detach: from="+fromTh+" to="+toTh);
            if (fromTh != -1) {
                maapi.detach(fromTh);
            }
            if (toTh != -1) {
                maapi.detach(toTh);
            }
        } catch (Exception e) {
            traceInfo(worker, "INFO: maapiDetach() Exception: "+e.getMessage());
        }
    }


    /**
     * Return true if device reply is an error
     * @param
     * @return
     */
    protected boolean isExecError(String res) {
        return res.contains("Invalid input ") || res.contains("syntax error:");
    }


    /**
     *
     * @param
     * @throws Exceptions
     */
    private void print_line_wait_oper(NedWorker worker, int cmd, String line, int retrying)
        throws NedException, IOException, SSHSessionException, ApplyException {
        print_line_wait_oper0(worker, cmd, line, retrying, this.readTimeout);
    }

    private void print_line_wait_oper(NedWorker worker, int cmd, String line, int retrying, int timeout)
        throws NedException, IOException, SSHSessionException, ApplyException {
        print_line_wait_oper0(worker, cmd, line, retrying, timeout);
        setReadTimeout(worker);
    }

    private void print_line_wait_oper0(NedWorker worker, int cmd, String line, int retrying, int timeout)
        throws NedException, IOException, SSHSessionException, ApplyException {

        traceVerbose(worker, "Sending(oper): '"+line+"'");

        // Send line and wait for echo
        session.print(line+"\n");
        session.expect(new String[] { Pattern.quote(line) }, worker);

        // Reset timeout after echo in case expect() reset timeout or echo slow
        resetTimeout(worker, timeout, 0);

        // Wait for prompt
        boolean loop = true;
        NedExpectResult res = null;
        while (loop) {
            traceVerbose(worker, "Waiting for oper prompt");
            res = session.expect(new String[] {
                    "Overwrite the previous NVRAM configuration\\?\\[confirm\\]",
                    "Warning: Saving this config to nvram may corrupt any network",
                    "Destination filename \\[\\S+\\][\\?]?\\s*$",
                    PRIVEXEC_PROMPT}, worker);
            switch (res.getHit()) {
            case 0:
                // Overwrite the previous NVRAM configuration
                traceInfo(worker, "Sending 'y'");
                session.print("y");
                break;
            case 1:
                // Warning: Saving this config to nvram may corrupt any network
                // management or security files stored at the end of nvram.
                // Continue? [no]: no
                // % Configuration buffer full, can't add command: access-list 99
                // %Aborting Save. Compress the config,
                // Save it to flash or Free up space on device[OK]
                // Confirm question with "no" then throw Exception
                traceInfo(worker, "Sending 'no'");
                session.println("no");
                final String failtxt = res.getText() + " " + expectGetMatch(res).trim();
                throw new ApplyException(line, failtxt, true, false);
            case 2:
                // Destination filename
                traceInfo(worker, "Sending newline (destination filename)");
                session.println("");
                break;
            default:
                loop = false;
                break;
            }
        }

        //
        // Check device reply
        //

        // Retries
        String reply = res.getText().trim();
        if (reply.contains("Device or resource busy")) {
            if (retrying >= configOutputMaxRetries) {
                throw new ApplyException(line, reply, true, false); // Give up retrying
            }
            // Sleep and retry
            sleep(worker, configOutputRetryInterval, true);
            print_line_wait_oper(worker, cmd, line, retrying + 1);
            return;
        }

        // Check for device errors:
        if (reply.toLowerCase().contains("error")
            || isExecError(reply)
            || reply.toLowerCase().contains("failed")) {

            // Ignore dynamic warnings
            for (int n = 0; n < dynamicWarning.size(); n++) {
                if (findString(dynamicWarning.get(n), reply) >= 0) {
                    traceInfo(worker, "ignoring dynamic oper warning on: "+stringQuote(reply));
                    return;
                }
            }

            // Throw exception
            throw new ApplyException(line, reply, true, false);
        }
    }


    /**
     *
     * @param
     * @return
     * @throws Exception
     */
    protected String print_line_exec(NedWorker worker, String line) throws Exception {

        // Send command and wait for echo
        session.print(line + "\n");
        session.expect(new String[] { Pattern.quote(line) }, worker);

        // Return command output
        try {
            return session.expect(PRIVEXEC_PROMPT, worker);
        } catch (Exception e) {
            // Possibly a timeout, try return the input data from the buffer
            NedExpectResult res = session.expect(new Pattern[] { Pattern.compile(".*", Pattern.DOTALL) },
                                                 true, 0, worker);
            String msg = e.getMessage() + " waiting for "+stringQuote(line);
            String match = expectGetMatch(res).trim();
            if (match.isEmpty()) {
                msg += ", no response from device";
            } else {
                msg += (", blocked on "+stringQuote(match));
            }
            traceInfo(worker, "print_line_exec() ERROR: "+msg);
            throw new IOException(msg);
        }
    }


    /**
     * Same as print_line_exec except also checks simulated ned-settings
     * @param
     * @return
     * @throws Exception
     */
    protected String print_line_simulated(NedWorker worker, String line) throws Exception {
        // ned-setting cisco-ios developer simulate-show *
        String simulated = simulateShow(worker, line);
        if (simulated != null) {
            return simulated;
        }
        return print_line_exec(worker, line);
    }


    /**
     *
     * @param
     * @return
     * @throws Exception
     */
    protected String print_line_exec(NedWorker worker, String line, int timeout) throws Exception {

        // Send command and wait for echo
        session.print(line + "\n");
        session.expect(new String[] { Pattern.quote(line) }, worker);

        // Reset timeout after echo in case expect() reset timeout or echo slow
        this.lastTimeout = resetTimeout(worker, timeout, 0);

        // Return command output
        return session.expect(PRIVEXEC_PROMPT, worker);
    }


    /**
     * Check if path exists
     * @param
     * @return
     */
    protected boolean maapiExists(NedWorker worker, int th, String path) {
        path = MaapiUtils.normalizePath(path);
        try {
            if (maapi.exists(th, path)) {
                traceDev(worker, "maapiExists("+path+") = true");
                return true;
            }
        } catch (Exception e) {
            logError(worker, "maapiExists("+path+") ERROR: ", e);
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
     * Get value from CDB using Maapi
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
    protected String maapiGetLeafString(NedWorker worker, int th, String path, String defaultValue) {
        String leaf = maapiGetLeafString(worker, th, path);
        if (leaf != null) {
            return leaf;
        }
        return defaultValue;
    }
    protected String maapiGetLeafString(NedWorker worker, int th, String path, boolean decrypt) {
        String leaf = maapiGetLeafString(worker, th, path);
        if (leaf == null) {
            return null;
        }
        try {
            return mCrypto.decrypt(leaf);
        } catch (Exception e) {
            return leaf;
        }
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
     */
    protected long maapiGetLeafLong(NedWorker worker, int th, String path, long defaultValue) {
        long val = defaultValue;
        String string = maapiGetLeafString(worker, th, path);
        if (string != null) {
            val = Long.parseLong(string);
        }
        return val;
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
                traceInfo(worker, "'" + path + "' is empty (" + num + ")");
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
                    } else if (entry[l].startsWith(PREFIX)) {
                        entry[l] = entry[l].replaceFirst(PREFIX, "");
                    }
                    traceDev(worker, "LIST["+n+","+l+"] = "+entry[l]);
                }
                list.add(entry);
            }

            traceInfo(worker, "'" + path + "' read " + numLeaves + " leaves in "
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
                } else if (entry[l].startsWith(PREFIX)) {
                    entry[l] = entry[l].replaceFirst(PREFIX, "");
                }
                traceDev(worker, "ENTRY["+l+"] = "+entry[l]);
            }

            traceInfo(worker, "'" + path + "' read " + numLeaves + " leaves "
                      + String.format("[%d ms]", tick(start)));
            return entry;

        } catch (Exception e) {
            throw new NedException("Internal ERROR in maapiGetObjects(): " + e.getMessage(), e);
        }
    }


    /**
     * Get config from CDB
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
                traceInfo(worker, "maapiGetConfig ERROR: failed to get "+ path);
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
            traceInfo(worker, "maapiGetConfig ERROR: read exception "+ e.getMessage());
            return null;
        }

        String[] lines = sb.toString().split("\n");
        if (lines.length < 5) {
            return null; // output does not contain 'devices device <device-id>\n config\n' + ' !\n!\n'
        }

        sb = new StringBuilder();
        for (int n = 2 + trimLevel; n < lines.length - 2 - trimLevel; n++) {
            String line = lines[n].substring(2);
            if (line.trim().startsWith("ios:") || line.trim().startsWith("no ios:")) {
                line = line.replaceFirst("ios:", "");
            }
            sb.append(line+"\n");
        }

        String data = sb.toString();
        traceDev(worker, "MAAPI_GET_AFTER=\n"+data);
        return data;
    }


    /**
     * tailf:cli-range-list-syntax leaf-list to int[] ArrayList
     * @param
     * @return
     */
    protected ArrayList<String> rangeListToArray(String leafListVal) {
        ArrayList<String> list = new ArrayList<>();
        if (leafListVal == null || leafListVal.isEmpty()) {
            return list;
        }
        final String[] ranges = leafListVal.split(",");
        for (int r = 0; r < ranges.length; r++) {
            Pattern p = Pattern.compile("(\\d+)(?:[-](\\d+))?");
            Matcher m = p.matcher(ranges[r]);
            if (!m.find()) {
                continue;
            }
            int start = Integer.parseInt(m.group(1));
            int end = m.group(2) != null ? Integer.parseInt(m.group(2)) : start;
            for (int v = start; v <= end; v++) {
                list.add(Integer.toString(v));
            }
        }
        return list;
    }


    /**
     *
     * @param
     * @return
     */
    private boolean hasPolice(String police) {
        return iospolice.contains(police);
    }


    /**
     *
     * @return
     */
    private boolean isDevice() {
        return !isNetsim();
    }


    /**
     *
     * @return
     */
    private boolean isOnlineDevice() {
        return session != null & offlineData == null && syncFile == null && isDevice();
    }


    /**
     * Is NETSIM device
     * @return
     */
    @Override
    public boolean isNetsim() {
        return iosmodel.contains("NETSIM");
    }


    /**
     * Check if line is top exit
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
     * Inject line(s) with read/inject-config or write/inject-command ned-settings
     * @param
     * @return
     * @throws NedException
     */
    private String injectData(NedWorker worker, String data, String[] entry, String dir) throws NedException {

        String regex = entry[1];
        final String line = entry[2];
        final String where = entry[3];
        if (where == null) {
            throw new NedException("ned-settings: inject missing 'where' value");
        }

        // where == first | where == last
        if ("first".equals(where) || (regex == null && where.startsWith("before"))) {
            if (regex != null && getMatch(data, "("+regex+"(?:[\r])?[\n])") == null) {
                return data;
            }
            traceInfo(worker, "transformed "+dir+" injected: "+stringQuote(line)+" first in config");
            return line + "\n" + data;
        } else if ("last".equals(where) || (regex == null && where.startsWith("after"))) {
            if (regex != null && getMatch(data, "("+regex+"(?:[\r])?[\n])") == null) {
                return data;
            }
            traceInfo(worker, "transformed "+dir+" injected: "+stringQuote(line)+" last in config");
            return data + line + "\n";
        }

        // append to regex
        if (where.contains("-topmode")) {
            regex = entry[1].trim() + "(?:[\r])?\n.+?\n(!|exit)";
        }
        regex += "(?:[\r])?[\n]";

        Pattern p = Pattern.compile(regex, Pattern.DOTALL);
        Matcher m = p.matcher(data);

        // Special (slow) case for after-last and after-topmode
        String insert;
        if ("after-last".equals(where) || "after-topmode".equals(where)) {
            int end = -1;
            String[] groups = null;
            while (m.find()) {
                end = m.end(0);
                groups = fillGroups(m);
            }
            if (end != -1) {
                try {
                    insert = fillInjectLine(worker, line + "\n", where, groups, dir);
                } catch (Exception e) {
                    throw new NedException("ned-settings: malformed inject regexp '"+entry[1]+"' : "+e.getMessage());
                }
                data = data.substring(0, end) + insert + "\n" + data.substring(end);
            }
        }

        else {
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String replacement = m.group(0);
                try {
                    insert = fillInjectLine(worker, line + "\n", where, fillGroups(m), dir);
                } catch (Exception e) {
                    throw new NedException("ned-settings: malformed inject regexp '"+entry[1]+"' : "+e.getMessage());
                }
                if ("before-first".equals(where) || "before-topmode".equals(where)) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(insert + replacement));
                    break;
                } else if ("before-each".equals(where)) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(insert + replacement));
                } else if ("after-each".equals(where)) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(replacement + insert));
                }
            }
            m.appendTail(sb);
            data = sb.toString();
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
            int num = (int)(insert.charAt(i+1) - '0');
            insert = insert.substring(0,i) + groups[num] + insert.substring(i+2);
            offset = offset + groups[num].length() - 2;
        }

        traceInfo(worker, "transformed "+dir+" injected "+stringQuote(insert)+" "+where+" "+stringQuote(groups[0]));

        return insert;
    }


    /**
     * Sleep for milliseconds
     * @param
     */
    private void sleep(NedWorker worker, long milliseconds, boolean log) {
        if (log) {
            traceVerbose(worker, "Sleeping " + milliseconds + " milliseconds");
        }
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            traceInfo(worker, "sleep interrupted");
            Thread.currentThread().interrupt();
        }
        if (log) {
            traceVerbose(worker, "Woke up from sleep");
        }
    }


    /**
     * Decrypt NSO password
     * @param
     * @return
     */
    private String maapiDecryptLine(NedWorker worker, String line) {
        // "des3-cbc-encrypted-string", // $7$
        // "aes-cfb-128-encrypted-string", // $8$ and $4$ with fixed IV (deprecated)
        // "aes-256-cfb-128-encrypted-string" // $9$
        Pattern p = Pattern.compile("( \\$[4789]\\$[^\\s]*)");
        Matcher m = p.matcher(line);
        while (m.find()) {
            String password = line.substring(m.start() + 1, m.end());
            try {
                password = password.replace("\\n", ""); // NSO bug patch
                String decrypted = mCrypto.decrypt(password);
                if (isNetsim() && decrypted.contains(";") && !decrypted.startsWith("\"")) {
                    decrypted = "\"" + decrypted + "\"";
                }
                traceVerbose(worker, "transformed => Maapi.decrypted '"+password+"'");
                line = line.substring(0, m.start()+1)
                    + decrypted
                    + line.substring(m.end(), line.length());
            } catch (Exception e) {
                // Ignore exceptions, since can't tell if password is NSO or device encrypted
                traceDev(worker, "Failed to Maapi.decrypt '"+password+"' : "+e.getMessage());
                return line;
            }
            m = p.matcher(line);
        }
        return line;
    }


    /**
     * Maapi decrypt line
     * @param
     * @return
     */
    private String maapiDecrypt(String line) {

        if (line == null || line.isEmpty()) {
            return line;
        }

        Pattern p = Pattern.compile("\\$[4789]\\$[^\\s]*");
        Matcher m = p.matcher(line);
        if (!m.find()) {
            return line;
        }

        try {
            line = mCrypto.decrypt(line);
        } catch (Exception e) {
            // Ignore
        }

        return line;
    }


    /**
     *
     * @param
     * @return
     */
    private static String nedCmdName(int cmd) {
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
    private static String nedCmdFullName(int cmd) {
        if (cmd == NedCmd.ABORT_CLI) {
            return "ABORT";
        }
        if (cmd == NedCmd.REVERT_CLI) {
            return "REVERT";
        }
        return "APPLY-CONFIG";
    }


    /**
     * Set user session
     * @throws Exceptions
     */
    private void setUserSession(NedWorker worker) throws ConfException, IOException {
        try {
            maapi.getMyUserSession();
        } catch (Exception ignore) {
            maapi.setUserSession(worker.getUsid());
        }
    }


    /**
     *
     * @param
     * @return
     */
    private String stringInsertCtrlV(String line) {
        if (line.indexOf('?') < 0) {
            return line;
        }
        return line.replace("?", (char)(0x16)+"?");
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
     * @return
     */
    private static String getString(String buf, int offset) {
        int nl = buf.indexOf('\n', offset);
        if (nl < 0) {
            return buf;
        }
        return buf.substring(offset, nl).trim();
    }


    /**
     * Read file from disk
     * @param
     * @return
     * @throws IOException
     */
    private String readFile(String file, boolean supportTrace) throws IOException {
        BufferedReader reader = new BufferedReader(new java.io.FileReader(file));
        try {
            String line;
            StringBuilder sb = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                sb.append(line);
                sb.append("\r\n");
            }

            // Extract config from first running-config, beginning with "Current configuration"
            if (supportTrace && sb.indexOf("- NED VERSION: ") > 0) {
                if (sb.indexOf(" NED VERSION: cisco-ios ") < 0) {
                    throw new IOException("'"+file+"': not a cisco-ios trace");
                }
                int s = sb.indexOf("\nCurrent configuration");
                if (s < 0) {
                    throw new IOException("'"+file+"': missing 'Current configuration'");
                }
                sb = sb.delete(0,s);
                s = sb.indexOf("\nend");
                if (s < 0) {
                    throw new IOException("'"+file+"': missing 'end'");
                }
                sb = sb.delete(s + 5, sb.length());
                sb.append("\n");
            }

            return sb.toString();
        } finally {
            reader.close();
        }
    }


    /**
     * Like NedString.stringDequote except that it preserves single backslash
     * @param
     * @return
     */
    protected static String textDequote(String aText) {
        if (aText.indexOf('"') != 0) {
            return aText;
        }
        aText = aText.substring(1,aText.length()-1);
        StringBuilder result = new StringBuilder();
        StringCharacterIterator iterator = new StringCharacterIterator(aText);
        char c1 = iterator.current();
        while (c1 != CharacterIterator.DONE) {
            if (c1 == '\\') {
                char c2 = iterator.next();
                if (c2 == CharacterIterator.DONE) {
                    result.append(c1);
                } else if (c2 == 'b') {
                    result.append('\b');
                } else if (c2 == 'n') {
                    result.append('\n');
                } else if (c2 == 'r') {
                    result.append('\r');
                } else if (c2 == 'v') {
                    result.append((char) 11); // \v
                } else if (c2 == 'f') {
                    result.append('\f');
                } else if (c2 == 't') {
                    result.append('\t');
                } else if (c2 == 'e') {
                    result.append((char) 27); // \e
                } else if (c2 == '\\') {
                    result.append('\\');
                } else {
                    result.append(c1);
                    result.append(c2);
                }
            } else {
                result.append(c1);
            }
            c1 = iterator.next();
        }
        return result.toString();
    }


    /**
     * Trim to single blank between words
     * @param
     * @return
     */
    protected String trimBlanks(String line) {
        StringBuilder sb = new StringBuilder();
        StringCharacterIterator it = new StringCharacterIterator(line);
        char ch = it.current();
        boolean trim = false;
        while (ch != CharacterIterator.DONE) {
            char nextCh = it.next();
            if (trim && ch == ' ' && nextCh == ' ') {
                continue;
            }
            if (ch == '\n') {
                trim = false;
            } else if (ch != ' ') {
                trim = true;
            }
            sb.append(ch);
            ch = nextCh;
        }
        return sb.toString();
    }


    /**
     * isValidAddressAndMask
     * @param
     * @return True if address is valid, i.e. matches mask bits
     */
    private boolean isValidAddressAndMask(String addrAndMask) {
        Pattern p = Pattern.compile(" ([0-9]+[.][0-9]+[.][0-9]+[.][0-9]+)[ ]+([0-9]+[.][0-9]+[.][0-9]+[.][0-9]+)");
        Matcher m = p.matcher(addrAndMask);
        if (!m.find()) {
            return true;
        }
        long addr = addressToLong(m.group(1));
        long mask = addressToLong(m.group(2));
        return (addr & ~mask) == 0;
    }


    /**
     * Convert String IPv4 address to long
     * @param
     * @return
     */
    private long addressToLong(String ipAddress) {
        long result = 0;
        String[] ipAddressInArray = ipAddress.split("\\.");
        for (int i = 3; i >= 0; i--) {
            long ip = Long.parseLong(ipAddressInArray[3 - i]);
            result |= ip << (i * 8);
        }
        return result;
    }
}
