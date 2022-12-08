"""CLI parse tree output plugin
Generates a json file to use for parsing CLI defined in YANG module.
"""
import re
import json
import optparse

from pyang import util
from pyang import plugin
from pyang import statements
from pyang import error

def pyang_plugin_init():
    plugin.register_plugin(JsonDumpPlugin())

class JsonDumpPlugin(plugin.PyangPlugin):
    def add_output_format(self, fmts):
        fmts['json'] = self

    def add_opts(self, optparser):
        optlist = [
            optparse.make_option("--json-pretty",
                                 dest="json_pretty",
                                 action="store_true",
                                 help="""Pretty-print json-output"""),
            optparse.make_option("--json-compact-need-when",
                                 dest="json_compact_when",
                                 action="store_true",
                                 help="""Include local when expressions in compact containers"""),
            optparse.make_option("--json-cli",
                                 dest="json_cli",
                                 action="store_true",
                                 help="""Process yang from CLI-parser perspective"""),
            optparse.make_option("--json-fwhen", action="append", dest="json_fwhen", type="string",
                                 help="""One or more xpaths for which to force include of when-expression"""),
            optparse.make_option("--json-cli-module", action="store", dest="json_cli_module", type="string",
                                 help="""Name of module with cli-extensions"""),
            optparse.make_option("--json-frelaxed", action="append", dest="json_frelaxed", type="string",
                                 help="""One or more xpaths for which to force relaxed-matching""")
            ]

        g = optparser.add_option_group("json format specific options")
        g.add_options(optlist)

        error.add_error_code('WAMBIGIOUS_NODE', 4,
                             "Suggest cli-disallow-value or pattern in %s")

        error.add_error_code('WUNHANDLED_NODE', 4,
                             "Node type not handled %s")

        error.add_error_code('BRACKETLEAFLIST_NODE', 4,
                             "CLI-parser can't handle bracket leaf-lists currently, found here %s")

        error.add_error_code('EMPTYDROP_NODE', 4,
                             "CLI-parser can't handle cli-drop-node-name in leaf of type empty currently, found here %s")

        error.add_error_code('UNSUPPORTED_CLIKEYFMT', 4,
                             "CLI-parser can't handle the cli-key-format found here %s")

        error.add_error_code('UNSUPPORTED_COMBO', 4,
                             "CLI-parser can't handle cli-annotations found here %s")

    def setup_ctx(self, ctx):
        pass

    def post_validate_ctx(self, ctx, modules):
        self.jsondumper = JsonDumper(ctx)
        self.jsondumper.build_tree(modules[0])

    def emit(self, ctx, modules, fd):
        self.jsondumper.emit(fd)

class XPathEval(object):
    def __init__(self, expr1, oper=None, expr2=None):
        self.oper = oper
        if type(expr1) == type("") and expr1.startswith("'"):
            expr1 = expr1[1:-1]
        self.expr1 = expr1
        self.expr2 = expr2

    def show(self):
        text = ""
        if self.oper == "not":
            text += self.oper + "("
            text += self.expr1.show()
            text += ")"
        elif self.oper == "contains":
            text += self.oper + "("
            text += self.expr1.show()
            text += ", '"
            text += self.expr2.show()
            text += "')"
        elif self.oper:
            text += "(" + self.expr1.show()
            text += " " + self.oper + " "
            text += self.expr2.show() + ")"
        else:
            text += self.expr1
        return text

    def expand_paths(self, stmt, forced=False):
        in_scope = True
        if self.oper:
            in_scope &= self.expr1.expand_paths(stmt, forced)
            if (self.expr2 and
                ((type(self.expr2.expr1) != type("")) or
                 (self.oper in ["and", "or"]))):
                in_scope &= self.expr2.expand_paths(stmt, forced)
        else:
            if self.expr1.startswith("/"):
                in_scope = False
                self.expr1 = re.sub("/\\S+:(.*)", "/\\1", self.expr1)
            else:
                rel_path = self.expr1
                is_hide = False
                if rel_path.startswith("./"):
                    rel_path = rel_path[2:]
                while(rel_path and rel_path.startswith("../")):
                    is_hide = stmt.tailf_annot("cli-hide-in-submode")
                    stmt = stmt.real_parent()
                    rel_path = rel_path[3:]
                    if ((not forced and stmt.is_context()) or
                        not stmt):
                        break
                in_scope = stmt and not (rel_path.startswith("..") or
                                         (stmt.is_context() and not is_hide))
                rel_path = (stmt.path() if stmt else "") + "/"  + rel_path
                if rel_path.startswith("//"):
                    rel_path = rel_path[1:]
                if ("#" in rel_path):
                    rel_path = re.sub("[#:]\\S[^/]*", "", rel_path)
                self.expr1 = rel_path
        return in_scope or forced

def strip_paren(expr):
    sub_expr = ""
    open = 1
    for c in expr[1:]:
        if c == ")":
            open -= 1
        elif c == "(":
            open += 1
        if open == 0:
            break
        sub_expr += c
    if open > 0:
        raise Exception("Unbalanced paranthesis: " + expr)
    rest = expr[len(sub_expr) + 2:].strip()
    return (sub_expr, rest)

operator_list = ["or(", "or ", "=", "!=", "and(", "and "]
def pop_operator(expr):
    oper = None
    rest = ""
    for op in operator_list:
        if expr.startswith(op):
            op = op.replace("(", "")
            op = op.strip()
            oper = op
            break
    else:
        raise Exception("invalid expr-part: " + expr)
    rest = expr[len(oper):]
    return (oper, rest.strip())

def pop_expression(expr):
    if expr.startswith("("):
        (expr1, rest) = strip_paren(expr)
        expr1 = parse_expr(expr1)
    elif expr.startswith("not("):
        (expr1, rest) = strip_paren(expr[3:])
        expr1 = XPathEval(parse_expr(expr1), "not")
    elif expr.startswith("contains("):
        (expr1, rest) = strip_paren(expr[8:])
        (path, value) = expr1.split(",", 1)
        expr1 = XPathEval(parse_expr(path.strip()), "contains", parse_expr(value.strip()))
    else:
        if expr[0] == "'":
            m = re.match("'[^']*'", expr)
            expr1 = m.group(0)
        else:
            expr1 = re.match("[^=^ ^!]+", expr).group(0)
        rest = expr[len(expr1):].strip()
        expr1 = XPathEval(expr1)
    return (expr1, rest.strip())

def parse_expr(expr):
    (expr1, rest) = pop_expression(expr)
    while rest:
        (oper, rest) = pop_operator(rest)
        if "=" in oper:
            (expr2, rest) = pop_expression(rest)
        else:
            expr2 = parse_expr(rest)
            rest = ""
        expr1 = XPathEval(expr1, oper, expr2)
    return expr1

def stmt_tailf_annot(self, annot):
    return self.search_one(("tailf-common", annot))

def stmt_is_leaf(self):
    return self.keyword == "leaf" or self.keyword == "leaf-list"

def stmt_is_key(self):
    return hasattr(self, "i_is_key") and self.i_is_key

def stmt_prefix_key_idx(self):
    keyidx = None
    if self.keyword == "choice" or self.keyword == "case":
        keyidx_list = [c.prefix_key_idx() for c in self.i_children]
        if all([keyidx == None for keyidx in keyidx_list]):
            keyidx = None
        elif len(set(keyidx_list)) > 1:
            raise Exception("Can't have mixed cli-prefix-key children in choice/case: "  + self.path())
        else:
            keyidx = keyidx_list[0]
    else:
        pk = self.tailf_annot("cli-prefix-key")
        if pk:
            keyidx = 1
            if pk.tailf_annot("cli-before-key"):
                keyidx = int(pk.tailf_annot("cli-before-key").arg)
    return keyidx

def stmt_prefix_keys(self):
    pref_keys = []
    if hasattr(self, "i_children"):
        for c in self.i_children:
            keyidx = c.prefix_key_idx()
            if keyidx:
                pref_keys.append((keyidx, c))
    return pref_keys

def stmt_hide_in_submode(self):
    is_hide = False
    if self.keyword == "choice" or self.keyword == "case":
        has_hide_child = any([len(c.hide_in_submode_children()) > 0 for c in self.i_children])
        if has_hide_child and not all([len(c.hide_in_submode_children()) > 0 for c in self.i_children]):
            raise Exception("Can't have mixed cli-hide-in-submode children in choice/case: "  + self.path())
        is_hide = has_hide_child
    else:
        is_hide = self.tailf_annot("cli-hide-in-submode")
    return is_hide

def stmt_hide_in_submode_children(self):
    hide_sm = []
    if hasattr(self, "i_children"):
        for c in [c for c in self.i_children if c.hide_in_submode()]:
            hide_sm.append(c)
    return hide_sm

def stmt_plain_children(self):
    children = []
    for c in self.i_children:
        if self.is_context() and c.hide_in_submode():
            continue
        if (not c.is_key() and
            not c.tailf_annot("cli-prefix-key") and
            not (c.keyword == "choice" and c.prefix_key_idx()) and
            (c.keyword != ('tailf-common', 'action'))):
            children.append(c)
    return children

def stmt_node_token(self):
    if (self.tailf_annot("cli-drop-node-name") or
        (self.is_key() and not self.tailf_annot("cli-expose-key-name")) or
        (self.keyword == "choice") or
        (self.keyword == "case")):
        name = ""
    else:
        alt_name = self.tailf_annot("alt-name")
        name = self.arg if alt_name is None else alt_name.arg
    return name

def stmt_is_context(self):
    result = False
    if self.parent == None:
        result = True
    elif self.keyword == "list":
        result = not self.tailf_annot("cli-suppress-mode")
    elif self.keyword == "container":
        result = self.tailf_annot("cli-add-mode")
    return result

def stmt_path(self):
    path = "/"
    if self.parent:
        path = statements.mk_path_str(self)
    if self.keyword == "choice":
        path += "#" + self.arg
    if self.keyword == "case":
        path = self.parent.path()
        path += ":" + self.arg
    return path

def stmt_real_parent(self):
    p = self.parent
    while p and (p.keyword == "choice" or p.keyword == "case"):
        p = p.parent
    return p

def stmt_in_uses(self):
    return [ustmt for ustmt in self.i_uses] \
        if (hasattr(self, "i_uses") and
            self.i_uses) else []

def stmt_in_sequence(self):
    p = self.parent
    while p and not p.is_context():
        if p.tailf_annot("cli-sequence-commands"):
            return True
        p = p.parent
    return False

def stmt_in_compact(self):
    p = self.parent
    while p and not p.is_context():
        if (p.tailf_annot("cli-compact-syntax") and
            p.keyword == "container"):
            return True
        p = p.parent
    return False

def stmt_in_grouping(self):
    grp = None
    if hasattr(self, "i_uses_top"):
        stmt = self
        while not stmt.i_uses_top:
            stmt = stmt.parent
        grp = stmt.i_uses[-1].arg
    return grp

_data_keywords = {
    'leaf', 'leaf-list', 'container', 'list', 'choice', 'case',
    'anyxml', 'rpc', 'notification',
    # Data in a more generic sense
    'type', 'default', 'key', 'presence', 'uses',
    #
    ("tailf-common", "codepoint"),
    ("tailf-common", "action"),
    ("tailf-common", "callpoint"),
}

def stmt_to_dict(self, flatten_level=-1, has_leafref=False):
    if self.arg == None:
        name = None
    elif type(self.arg) == type(""):
        name = self.arg
    else:
        name = self.arg[0] + ":" + self.arg[1]
    if type(self.keyword) == type(""):
        keyword = self.keyword
    else:
        keyword = self.keyword[0] + ":" + self.keyword[1]
    subs = list()
    for s in self.substmts:
        if (not s.keyword in _data_keywords) or (has_leafref and s.keyword == "type"):
            subs.append(s.to_dict(flatten_level=(flatten_level-1)))
    if flatten_level > 0:
        dump = dict()
        dump["keyword"] = keyword
        if name != None:
            dump["name"] = name
            if subs:
                dump["substmts"] = subs
            if keyword == "list":
                keys = self.search_one("key")
                dump["keys"] = keys.arg.split(" ")
            elif keyword.startswith("leaf"):
                defval = self.search_one("default")
                if defval:
                    dump["default"] = defval.arg
            elif keyword == "container":
                presence = self.search_one("presence")
                if presence:
                    dump["presence"] = presence.arg
    else:
        dump = list()
        dump.append(keyword)
        if name != None:
            dump.append(name)
        if subs:
            dump.append(subs)
    return dump

def stmt_is_optional(self):
    if (self.tailf_annot("cli-optional-in-sequence") or
        (self.tailf_annot("cli-prefix-key") and not self.in_sequence())):
        return True
    elif self.keyword == "case" or self.keyword == "choice":
        return all([c.is_optional() for c in self.i_children])
    return False

statements.Statement.tailf_annot = stmt_tailf_annot
statements.Statement.is_leaf = stmt_is_leaf
statements.Statement.is_key = stmt_is_key
statements.Statement.prefix_key_idx = stmt_prefix_key_idx
statements.Statement.prefix_keys = stmt_prefix_keys
statements.Statement.hide_in_submode = stmt_hide_in_submode
statements.Statement.hide_in_submode_children = stmt_hide_in_submode_children
statements.Statement.plain_children = stmt_plain_children
statements.Statement.node_token = stmt_node_token
statements.Statement.is_context = stmt_is_context
statements.Statement.path = stmt_path
statements.Statement.real_parent = stmt_real_parent
statements.Statement.in_uses = stmt_in_uses
statements.Statement.in_sequence = stmt_in_sequence
statements.Statement.in_compact = stmt_in_compact
statements.Statement.in_grouping = stmt_in_grouping
statements.Statement.to_dict = stmt_to_dict
statements.Statement.is_optional = stmt_is_optional

class Type(object):
    def __init__(self, typename):
        self.typename = typename
        self.leafref = None

    def emit(self):
        return self.typename

    def match_any(self):
        return False

class Union(object):
    def __init__(self, types):
        self.types = types
        self.leafref = None

    def emit(self):
        dump = list()
        for t in self.types:
            dump.append(t.emit())
        return dump

    def match_any(self):
        return any([t.match_any() for t in self.types])

class Enumeration(Type):
    def __init__(self, tokens, descriptions):
        Type.__init__(self, "enumeration")
        self.tokens = tokens
        self.descriptions = descriptions

    def emit(self):
        dump = dict()
        dump["typename"] = self.typename
        dump["tokens"] = self.tokens
        if self.descriptions:
            dump["descriptions"] = self.descriptions
        return dump

class SimpleType(Type):
    def __init__(self, typename):
        Type.__init__(self, typename)

    def match_any(self):
        return self.typename.startswith("string")

class TypeDef(Type):
    def __init__(self, typename, type):
        Type.__init__(self, "#" + typename)
        self.type = type

class ComplexType(Type):
    def __init__(self, typename, patterns):
        Type.__init__(self, typename)
        self.patterns = patterns

    def emit(self):
        dump = dict()
        dump["typename"] = self.typename
        dump["patterns"] = self.patterns
        return dump

def extract_type(stmt, jsondumper):
    if stmt.keyword == "type":
        typenode = stmt
    else:
        typenode = stmt.search_one("type")
    typename = typenode.arg
    patterns = None
    if typename == "empty":
        pass
    elif stmt.tailf_annot("cli-boolean-no"):
        typename = "boolean_no"
        pass
    elif typename == "union":
        return Union([extract_type(t, jsondumper) for t in typenode.substmts if t.keyword == "type"])
    elif typename == "enumeration":
        tokens = list()
        descriptions = list()
        for e in typenode.substmts:
            if e.keyword == "enum":
                tokens.append(e.arg)
                dstmt = None
                if jsondumper.ctx.opts.json_cli:
                    dstmt = e.tailf_annot("info")
                else:
                    dstmt = e.search_one("description")
                descriptions.append(dstmt.arg if dstmt else "")
        return Enumeration(tokens, descriptions if any(descriptions) else [])
    elif typename == "leafref":
        ref,_ = stmt.i_leafref_ptr
        reftype = extract_type(ref, jsondumper)
        if isinstance(reftype, Type):
            reftype.leafref = typenode.search_one("path").arg
        return reftype
    elif typename == "string":
        pat = typenode.search("pattern")
        length = typenode.search_one("length")
        if length:
            length = length.arg.replace(" ", "")
            typename += length
        if pat:
            patterns = list()
            for p in pat:
                patterns.append(p.arg)
    elif typename == "decimal64":
        fd = typenode.search_one("fraction-digits").arg
        patterns = "[+\\-]?[0-9]+(?:\\.[0-9]{0,%s})?" % fd
    elif typename == "boolean" and not stmt.tailf_annot("cli-boolean-no"):
        pass
    elif typename == "identityref":
        # TODO
        pass
    elif re.match("(?:u?int(?:\\d+))", typename):
        rng = typenode.search_one("range")
        if rng:
            range = rng.arg
            typename += "r"
            if "|" in range:
                return Union([SimpleType(typename + subr.strip()) for subr in range.split("|")])
            else:
                typename += range.replace(" ", "")
    else:
        return jsondumper.lookup_typedef(stmt, typename)
    return ComplexType(typename, patterns) if patterns else SimpleType(typename)

class Node(object):

    def __init__(self, jsondumper, stmt):
        self.jsondumper = jsondumper
        self.stmt = stmt
        self.type = None
        self.max_words = None
        self.children = list()
        self.extract_node(stmt)
        if self.jsondumper.ctx.opts.json_cli and stmt.is_context():
            self.jsondumper.add_context(stmt)

    def emit(self):
        has_leafref = self.type and not(self.type.leafref is None)
        dump = self.stmt.to_dict(flatten_level=1, has_leafref=has_leafref)
        if self.type:
            dump["type"] = self.type.emit()
        if len(self.children) > 0:
            dump["children"] = list()
            for s in self.children:
                name = s.stmt.arg
                if s.stmt.keyword == "choice":
                    name = "#" + name
                dump["children"].append(name)

        if self.jsondumper.ctx.opts.json_cli:
            self.emit_cli(dump)

        return dump

    def emit_cli(self, dump):
        dump["token"] = self.stmt.node_token()
        if self.stmt.is_context():
            dump["is_context"] = "true"
        if (self.stmt.tailf_annot("cli-allow-join-with-value") or
            self.stmt.tailf_annot("cli-allow-join-with-key")):
            dump["is_joined"] = "true"
        match_as_sequence = False
        if self.stmt.tailf_annot("cli-sequence-commands"):
            dump["is_sequence"] = "true"
            match_as_sequence = True
        elif ((self.stmt.keyword == "list")
              or ((self.stmt.keyword == "case")
                  and self.stmt.real_parent().tailf_annot("cli-sequence-commands"))):
            match_as_sequence = True
        if match_as_sequence:
            dump["match_as_sequence"] = "true"
        if (self.stmt.tailf_annot("cli-compact-syntax")
            or ((self.stmt.keyword == "case") and
                (self.stmt.real_parent().tailf_annot("cli-compact-syntax") or
                 self.stmt.prefix_key_idx()))
            or self.stmt.is_context()):
            dump["is_compact"] = "true"
        if (self.stmt.tailf_annot("cli-flatten-container")
            or ((self.stmt.keyword == "case") and
                self.stmt.real_parent().tailf_annot("cli-flatten-container"))):
            # compact implied when flatten
            dump["is_compact"] = "true"
        if self.stmt.is_optional():
            dump["is_optional"] = "true"
        if self.stmt.tailf_annot("cli-incomplete-command"):
            dump["is_incomplete"] = "true"
        if self.stmt.tailf_annot("cli-incomplete-no"):
            dump["is_incomplete_no"] = "true"
        if self.stmt.tailf_annot("cli-full-command"):
            dump["is_full"] = "true"
        if self.stmt.tailf_annot("cli-break-sequence-commands"):
            dump["break_sequence"] = "true"
        if (self.stmt.tailf_annot("cli-range-list-syntax") or
            (self.stmt.is_key() and
             self.stmt.parent.tailf_annot("cli-range-list-syntax"))):
            dump["range_list"] = "true"
        if self.stmt.tailf_annot("cli-case-insensitive"):
            dump["case_insensitive"] = "true"
        if self.stmt.tailf_annot("cli-disallow-value"):
            dump["disallow_value"] = self.stmt.tailf_annot("cli-disallow-value").arg
        if self.max_words:
            dump["multi_word"] = self.max_words
        force_when = self.force_include_when()
        if (self.stmt.in_sequence()
            or
            (self.stmt.tailf_annot("cli-hide-in-submode") and
             self.stmt.parent.tailf_annot("cli-sequence-commands"))
            or
            force_when
            or
            (self.jsondumper.ctx.opts.json_compact_when and self.stmt.in_compact())):
            self.add_when(dump, force_when)
        defval = self.stmt.search_one("default")
        if not defval:
            defval = self.stmt.tailf_annot("key-default")
        if defval:
            dump["default"] = defval.arg
            # optional implied when has default value
            dump["is_optional"] = "true"
        if self.force_relaxed_match():
            dump["force_relaxed"] = "true"

        for c in self.children:
            if (((c.stmt.keyword == "leaf" and
                  hasattr(c.type, "typename") and
                  c.type.typename == "empty") or
                 c.stmt.search_one("presence")) and
                c.stmt.tailf_annot("cli-drop-node-name")):
                dump["auto_child"] = c.stmt.arg
                break

        self.check_metadata(dump)

    def force_include_when(self):
        force_include_when = False
        for xpath_re in self.jsondumper.ctx.opts.json_fwhen:
            if re.match(xpath_re, self.stmt.path()):
                force_include_when = True
                break
        return force_include_when

    def force_relaxed_match(self):
        force_relaxed = False
        for xpath_re in self.jsondumper.ctx.opts.json_frelaxed:
            if re.match(xpath_re, self.stmt.path()):
                force_relaxed = True
                break
        return force_relaxed

    def check_metadata(self, dump):
        md_list = self.stmt.search(("tailf-common", "meta-data"))
        for md in md_list:
            val = md.tailf_annot("meta-value")
            if not val:
                val = md.arg
                if val == "nedcom-parse-compact-syntax":
                    raise Exception('\x1b[1m*** NOTE: Update nedcom and replace tailf:meta-data "nedcom-parse-compact-syntax" with cli:parse-compact-syntax\x1b[0m')

    def add_when(self, dump, force_include_when):
        if self.stmt.search_one("when"):
            xpatheval = parse_expr(self.stmt.search_one("when").arg)
            if xpatheval.expand_paths(self.stmt, force_include_when):
                dump["when"] = xpatheval.show()
                if force_include_when:
                    dump["force_when"] = "true"
        else:
            in_uses = self.stmt.in_uses()
            parentuses = self.stmt.real_parent().search("uses") if self.stmt.real_parent() else None
            if in_uses and parentuses:
                when = list()
                for u in parentuses:
                    if u in in_uses and u.search_one("when"):
                        when.append(u.search_one("when").arg)
                if len(when) > 0:
                    if len(when) == 1:
                        xpatheval = parse_expr(when[0])
                        if xpatheval.expand_paths(self.stmt.real_parent(), force_include_when):
                            dump["when"] = xpatheval.show()
                            if force_include_when:
                                dump["force_when"] = "true"
                        else:
                            print("REMOVE USE_WHEN IN: " + self.stmt.path)
                    else:
                        raise Exception("Can't handle multiple when in uses chain")

    def extract_multi_word(self, stmt):
        multival = None
        if stmt.is_key():
            multival = stmt.tailf_annot("cli-multi-word-key")
        elif stmt.keyword == "leaf-list" and not stmt.tailf_annot("cli-range-list-syntax"):
            maxe = stmt.search_one("max-elements")
            if maxe:
                self.max_words = maxe.arg
            else:
                self.max_words = "0"
        else:
            multival = stmt.tailf_annot("cli-multi-value")
        if multival:
            if multival.tailf_annot("cli-max-words"):
                self.max_words = multival.tailf_annot("cli-max-words").arg
            else:
                self.max_words = "0"

    def extract_leaf(self, stmt):
        if self.jsondumper.ctx.opts.json_cli:
#             if (stmt.keyword == "leaf-list" and
#                 not stmt.tailf_annot("cli-range-list-syntax") and
#                 not stmt.tailf_annot("cli-list-syntax") and
#                 not stmt.tailf_annot("cli-flat-list-syntax")):
#                 self.jsondumper.add_warning("BRACKETLEAFLIST_NODE", stmt)
            self.extract_multi_word(stmt)
        self.type = extract_type(stmt, self.jsondumper)
#         if (self.jsondumper.ctx.opts.json_cli and
#             stmt.keyword == "leaf" and
#             stmt.tailf_annot("cli-drop-node-name") and
#             hasattr(self.type, "typename") and
#             self.type.typename == "empty"):
#             self.jsondumper.add_warning("EMPTYDROP_NODE", stmt)


    def extract_list(self, stmt):
        if self.jsondumper.ctx.opts.json_cli:
            keys = [c for c in stmt.i_children if c.is_key()]
            keyfmt = stmt.tailf_annot("cli-key-format")
            if keyfmt:
                if not all([((("$(%d)" % (i+1))) in keyfmt.arg) for i in range(len(keys))]):
                    self.jsondumper.add_warning("UNSUPPORTED_CLIKEYFMT", stmt)
            prefix_keys = stmt.prefix_keys()
            for (kix, key) in enumerate(keys):
                kix += 1
                for pk in [pk for (pix, pk) in prefix_keys if pix == kix]:
                    self.children.append(self.jsondumper.add_node(pk))
                key = self.jsondumper.add_node(key)
                self.children.append(key)
            if stmt.tailf_annot("cli-suppress-mode"):
                self.extract_children(stmt, stmt.plain_children())
            else:
                self.extract_children(stmt, stmt.hide_in_submode_children())
        else:
            self.extract_children(stmt, stmt.i_children)

    def extract_container(self, stmt):
        if self.jsondumper.ctx.opts.json_cli:
            if not stmt.tailf_annot("cli-add-mode"):
                self.extract_children(stmt, stmt.plain_children())
            else:
                self.extract_children(stmt, stmt.hide_in_submode_children())
        else:
            self.extract_children(stmt, stmt.i_children)

    def extract_children(self, stmt, children):
        for c in children:
            node = self.jsondumper.add_node(c)
            self.children.append(node)

        if self.jsondumper.ctx.opts.json_cli:
            def potentially_ambigious(c):
                return ((c.stmt.keyword == "leaf") and
                        c.stmt.tailf_annot("cli-drop-node-name") and
                        (not c.stmt.tailf_annot("cli-disallow-value")) and
                        c.type.match_any())
            if self.children:
                chk_children = self.children[:-1]
            else:
                chk_children = []
            # Potentially ambigious optional leaf with drop-node-name+string without pattern/disallow
            # (might need currently unimplemented back-track to be parse'able)
#             for c in chk_children:
#                 if (potentially_ambigious(c) and
#                     c.stmt.tailf_annot("cli-optional-in-sequence")):
#                     self.jsondumper.add_warning("WAMBIGIOUS_NODE", c.stmt)
# This is also indicative of slow parse, might need unnecesary back-track, potential remodel
#             if self.stmt.keyword == "case":
#                 chk_children = self.children
#                 if (len(self.children) == 1 and
#                     potentially_ambigious(self.children[0])):
#                     self.jsondumper.add_warning("WAMBIGIOUS_NODE", self.children[0].stmt)
#                     chk_children = []

    def extract_node(self, stmt):
        if stmt.is_leaf():
            self.extract_leaf(stmt)
        elif stmt.keyword == "list":
            self.extract_list(stmt)
        elif stmt.keyword == "container":
            self.extract_container(stmt)
        elif stmt.keyword == "choice" or stmt.keyword == "case":
            self.extract_children(stmt, stmt.i_children)
        else:
            self.jsondumper.add_warning("WUNHANDLED_NODE", stmt)

class Context(object):

    def __init__(self, jsondumper, stmt):
        self.jsondumper = jsondumper
        self.stmt = stmt
        self.children = list()
        self.analyze_node(stmt)
        self.is_module = False

    def emit(self):
        dump = dict()
        exitcmd = self.stmt.tailf_annot("cli-exit-command")
        if exitcmd:
            dump["exit_cmd"] = exitcmd.arg
        children = list()
        for m in self.children:
            name = m.stmt.arg
            if m.stmt.keyword == "choice":
                name = "#" + name
            children.append(name)
        if len(children) > 0:
            dump["children"] = children
        if self.is_module:
            dump["module"] = self.module
            dump["namespace"] = self.namespace
            dump["prefix"] = self.prefix
            dump["imports"] = self.imports
            dump["extensions"] = self.extensions
            dump["module_meta"] = self.module_meta
        return dump

    def analyze_node(self, stmt):
        for c in stmt.plain_children():
            node = self.jsondumper.add_node(c)
            self.children.append(node)

class JsonDumper(object):

    def __init__(self, ctx):
        self.ctx = ctx
        self.contexts = dict()
        self.nodes = dict()
        self.typedefs = dict()
        self.typedef_stmts = dict()

    def println(self, str):
        self.fd.write(str + "\n")

    def add_warning(self, tag, stmt):
        error.err_add(self.ctx.errors, stmt.pos, tag, (stmt.path()))

    def lookup_typedef(self, stmt, typename):
        if self.typedefs.has_key(typename):
            return self.typedefs[typename]
        typedef = statements.search_typedef(stmt, typename)
        if not typedef:
            typedef = self.lookup_global_type(typename, stmt)
        if not typedef:
            raise Exception("Undefined type here: '%s' (%s)" %
                            (typename, stmt.path()))
        typevalidator = extract_type(typedef, self)
        type = TypeDef(typename, typevalidator)
        self.typedefs[typename] = type
        return type

    def lookup_global_type(self, typename, stmt):
        typedef = None
        in_module = stmt.i_module
        if self.typedef_stmts.has_key(typename):
            return self.typedef_stmts[typename]
        grouping = stmt.in_grouping()
        # print("LOOKUP TYPE: %s (module: %s (%s))%s" % (typename, in_module.arg, stmt.pos, (" (grouping: %s)" % grouping) if grouping else ""))
        if grouping and ":" in grouping:
            in_module = self.all_modules[in_module.i_prefixes[grouping.split(":")[0]][0]]
        if ":" in typename:
            (prefix, nopref_typename) = typename.split(":")
            module_name = in_module.i_prefixes[prefix][0]
        else:
            nopref_typename = typename
            module_name = in_module.arg

        module = self.all_modules[module_name]
        for td in [t for t in module.substmts if t.keyword == "typedef"]:
            if td.arg == nopref_typename:
                typedef = td
                break

        self.typedef_stmts[typename] = typedef
        return typedef

    def named_capture_grp(self, grp):
        return "P<%s>" % grp

    def add_node(self, stmt):
#         nedx = stmt.search_one(('tailf-ned-cisco-nx', 'nedx'))
#         if nedx:
#             print("FOUND nedx in " + stmt.path())
#             for s in nedx.substmts:
#                 print("%s %s" % (s.keyword, s.arg))
        node = Node(self, stmt)
        self.nodes[node.stmt.path()] = node
        return node

    def add_context(self, stmt):
        context = Context(self, stmt)
        path = stmt.path()
        self.contexts[path] = context
        return context

    def build_tree(self, module):
        self.all_modules = dict()
        for m in self.ctx.modules.itervalues():
            self.all_modules[m.arg] = m
        self.all_modules[module.arg] = module
        self.module = self.add_context(module)
        self.module.is_module = True
        self.module.imports = dict()
        self.module.module = module.arg
        self.module.namespace = module.search_one("namespace").arg
        self.module.prefix = module.search_one("prefix").arg
        for imp in module.search("import"):
            pref = imp.search_one("prefix")
            self.module.imports[pref.arg] = imp.arg
        self.module.extensions = dict()
        for ext in module.search("extension"):
            if ext.search_one((self.ctx.opts.json_cli_module, "builtin")):
                continue
            defmap = {"direction":"both", "state":"any", "java-callback-method":None}
            extmap = dict()
            for (k,v) in defmap.iteritems():
                s = ext.search_one((self.ctx.opts.json_cli_module, k))
                v = s.arg if s else v
                if not v is None:
                    extmap[k] = v
            self.module.extensions[ext.arg] = extmap
        self.module.module_meta = list()
        for mmeta in module.search((self.ctx.opts.json_cli_module, "module-meta-data")):
            for c in mmeta.substmts:
                self.module.module_meta.append(c.to_dict(flatten_level=1))

    def emit_type(self, type_validator):
        dump = None
        if type(type_validator) == type([]):
            dump = list()
            for tv in type_validator:
                dump.append(self.emit_type(tv))
        else:
            dump = type_validator.emit()
        return dump

    def emit(self, fd):
        self.fd = fd
        parse_data = dict()
        if self.ctx.opts.json_cli:
            parse_data["cli"] = "true"
        if not self.ctx.opts.json_fwhen:
            self.ctx.opts.json_fwhen = []
        if not self.ctx.opts.json_frelaxed:
            self.ctx.opts.json_frelaxed = []
        parse_data["contexts"] = dict()
        parse_data["nodes"] = dict()
        for (path, context) in self.contexts.iteritems():
            parse_data["contexts"][path] = context.emit()
        for (path, node) in self.nodes.iteritems():
            parse_data["nodes"][path] = node.emit()
        types_dump = dict()
        for (typename, typedef) in self.typedefs.iteritems():
            types_dump[typename] = typedef.type.emit()
        parse_data["types"] = types_dump
        indent = 2 if self.ctx.opts.json_pretty else None
        json.dump(parse_data, self.fd, indent=indent)
