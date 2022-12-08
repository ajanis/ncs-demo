MAIN_YANG_MODULE = tailf-ned-cisco-ios-xr.yang
PACKAGE_NAME=iosxr
EXTRA_YANG_STATS_MODULES=ietf-interfaces.yang ietf-ip.yang

ENCAP_DOT1Q_AS_LEAF ?= False
YPP_VARS            ?= ENCAP_DOT1Q_AS_LEAF

#YANG_ID_MODULE=tailf-ned-cisco-ios-xr-id

# NOTE: This has been moved into cli:module-meta-data section in yang module
# PYANG_JSON_XARGS = --json-fwhen="^/route-policy$$" --json-fwhen="^/route-policy-edit$$"

JAVA_SRC := java/src/com/tailf/packages/ned/iosxr/namespaces/iosxrStats.java

all: all_cli

include nedcom.mk

ncsc-out/modules/fxs/ietf-ip.fxs: ncsc-out/modules/fxs/ietf-interfaces.fxs

java/src/com/tailf/packages/ned/iosxr/namespaces/iosxrStats.java: yang/tailf-ned-cisco-ios-xr-stats.yang
	$(NCSC) $(JFLAGS)/iosxrStats.java ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-stats.fxs
