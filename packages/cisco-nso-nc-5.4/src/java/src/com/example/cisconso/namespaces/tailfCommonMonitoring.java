/*
 * BEWARE BEWARE BEWARE BEWARE BEWARE BEWARE BEWARE BEWARE BEWARE
 * This file has been auto-generated by the confdc compiler.
 * Source: ../load-dir/tailf-common-monitoring.fxs
 * BEWARE BEWARE BEWARE BEWARE BEWARE BEWARE BEWARE BEWARE BEWARE
 */

package com.example.cisconso.namespaces;

import com.tailf.conf.ConfNamespace;

/** Autogenerated namespace class for YANG module tailf-common-monitoring.yang */
public class tailfCommonMonitoring extends ConfNamespace {
    public static final int hash = 1581623976;
    public int hash() {
        return tailfCommonMonitoring.hash;
    }

    public static final String id = "_cisco-nso-nc-5.4:cisco-nso-nc-5.4#http://tail-f.com/yang/common-monitoring";
    public String id() {
        return tailfCommonMonitoring.id;
    }

    public static final String uri = "_cisco-nso-nc-5.4:cisco-nso-nc-5.4#http://tail-f.com/yang/common-monitoring";
    public String uri() {
        return tailfCommonMonitoring.uri;
    }

    public String xmlUri() {
        return ConfNamespace.truncateToXMLUri(tailfCommonMonitoring.uri);
    }

    public static final String prefix = "tfcg";
    public String prefix() {
        return tailfCommonMonitoring.prefix;
    }

    public tailfCommonMonitoring() {}

    public static int stringToHash(String str) {
        return ConfNamespace.stringToHash(str);
    }

    public static String hashToString(int hash) {
        return ConfNamespace.hashToString(hash);
    }

}
