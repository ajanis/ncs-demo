BEGIN {
    print \
"// THIS FILE IS AUTO-GENERATED - DO NOT EDIT\n" \
"package com.tailf.packages.ned.nexus;\n" \
"\n" \
"import java.util.Collections;\n" \
"import java.util.HashMap;\n" \
"import java.util.Map;\n" \
"\n" \
"public class NexusNedLiveStatsMap\n" \
"{\n" \
"    private static final Map<String, String> MAP = createMap();\n" \
"\n" \
"    private static Map<String, String> createMap() {\n" \
"        Map<String, String> result = new HashMap<String, String>();\n";
}

($1 == "<container" || $1 == "<list" || $1 == "<leaf") && $NF !~ "/>$" {
    name = $2;
    gsub("^[^\"]*\"", "", name);
    gsub("\"[^\"]*$", "", name);
    path = path "/" name
}

$1 == "</container>" || $1 == "</list>" || $1 == "</leaf>" {
    gsub("/[^/]*$", "", path);
    if ($1 != "</leaf>" && nonterminated) {
        print \
"                      \"\");";
        nonterminated = 0;
    }
}

$1 == "<key" {
    key = $0;
    gsub(" *<key value=", "", key);
    gsub("/>", "", key);
    gsub("\"", "", key);
    delete keymap;
    split(key, keya, " ");
    for (k in keya) {
        keymap[keya[k]] = 1;
    }
}

$1 == "<tailf:cli-oper-info>" {
    oper_info = 1;
}

$1 == "</tailf:cli-oper-info>" {
    oper_info = 0;
}

$0 ~ "<tailf:text>" {
    if (oper_info) {
        decl = $0;
        gsub("^\\s*<tailf:text>", "", decl);
        gsub("</tailf:text>\\s*$", "", decl);
        gsub("&lt;", "<", decl);
        gsub("&gt;", ">", decl);
        gsub("\\\\", "\\\\", decl);
        if (decl ~ "LEAF:") {
            if (name in keymap) {
                print \
"                   \"<K>" name "<3>" substr(decl, 6) "<2>\" +" \
            } else {
                print \
"                      \"" name "<3>" substr(decl, 6) "<2>\" +" \
            }
        } else {
            print \
"        result.put(\"" path "\",";
            split(decl, decla, "<1>");
            for (d in decla) {
                print \
"                   \"" decla[d] "<1>\" +"   \
            }
            print \
"                   \"LEAVES:\" +";
        }
        nonterminated = 1;
    }
}

END {
    print \
"        return Collections.unmodifiableMap(result);\n" \
"    }\n" \
"\n" \
"    public static String getItem(String path, String item) {\n" \
"        String allItems = MAP.get(path);\n" \
"        if (allItems != null) {\n" \
"            String items[] = allItems.split(\"<1>\");\n" \
"            for (int i = 0; i < items.length; i++) {\n" \
"                if (items[i].startsWith(item)) {\n" \
"                    String s = items[i].substring(item.length());\n" \
"                    return s.replaceAll(\"<\\\\d>$\", \"\");\n" \
"                }\n" \
"            }\n" \
"        }\n" \
"        return null;\n" \
"    }\n" \
"}";
}
