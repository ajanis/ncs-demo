JAVA_PACKAGE = com.tailf.packages.ned.iosxr
JDIR = $(shell echo $(JAVA_PACKAGE) | sed 's/\./\//g')

NS  = namespaces

JFLAGS = --java-disable-prefix \
         --exclude-enums \
         --fail-on-warnings \
         --java-package $(JAVA_PACKAGE).$(NS) \
         --emit-java

YANG_MODULE = tailf-ned-cisco-iosxr
NED_ID_ARG = $(shell [ -x ${NCS_DIR}/support/ned-ncs-ned-id-arg ] && \
             ${NCS_DIR}/support/ned-ncs-ned-id-arg package-meta-data.xml.in)
ifeq ($(NED_ID_ARG),)
NED_ID_FILE = $(YANG_MODULE)-id
else
NED_ID_FILE = tailf-ned-id-$(shell echo $(NED_ID_ARG) | cut -d: -f2)
endif
JAVA_ID_FILE = $(shell echo $(YANG_MODULE) | \
           perl -pe 's/-ned//;s/(^|-)./uc($$&)/ge;s/-//ge;s/(.)/lc($$&)/e')Id

DIRS = ncsc-out ncsc-out/modules $(NFXS)         \
       ../load-dir ../shared-jar ../private-jar  \
       java/src/$(JDIR)/$(NS)

all: $(DIRS) ../package-meta-data.xml ned-id-file fxs javac
	cd ../netsim && $(MAKE) all
.PHONY: all

# Include standard NCS examples build definitions and rules
include $(NCS_DIR)/src/ncs/build/include.ncs.mk

YANG = yang/tailf-ned-cisco-ios-xr.yang       \
       yang/tailf-ned-cisco-ios-xr-stats.yang \
       yang/tailf-ned-cisco-ios-xr-meta.yang

SRC = $(wildcard yang/*.yang)

JAVA_NAMESPACES = \
	$(shell echo $(YANG:yang/%.yang=java/src/$(JDIR)/namespaces/%.java) | \
	    perl -pne 's/([a-zA-Z])-([a-zA-Z])/\1\U\2/g' |                    \
	    perl -pne 's/tailfNed//g')

$(DIRS):
	mkdir -p $@

../package-meta-data.xml: package-meta-data.xml.in
	rm -rf $@
	if [ -x ${NCS_DIR}/support/ned-make-package-meta-data ]; then \
	   ${NCS_DIR}/support/ned-make-package-meta-data $<;          \
	else                                                          \
	   cp $< $@;                                                  \
	fi
	chmod +w $@

ned-id-file:
	if [ -x ${NCS_DIR}/support/ned-make-package-meta-data ]; then   \
	    echo -n "$(NED_ID_FILE) is built by: ";                     \
	    echo "support/ned-make-package-meta-data";                  \
	else                                                            \
	    $(NCSC) -c yang/$(NED_ID_FILE).yang                         \
	    -o ../load-dir/$(NED_ID_FILE).fxs;                          \
	fi
	$(NCSC)                                                         \
	    $(JFLAGS) java/src/$(JDIR)/namespaces/$(JAVA_ID_FILE).java  \
	    ../load-dir/$(NED_ID_FILE).fxs

fxs:	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr.fxs       \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-stats.fxs \
	../load-dir/tailf-ned-cisco-ios-xr-meta.fxs

namespaces: $(JAVA_NAMESPACES)

java/src/$(JDIR)/namespaces/CiscoIosXr.java: \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr.fxs
	$(NCSC) $(JFLAGS) $@ $<

java/src/$(JDIR)/namespaces/CiscoIosXrStats.java: \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-stats.fxs
	$(NCSC) $(JFLAGS) $@ $<

java/src/$(JDIR)/namespaces/CiscoIosXrMeta.java: \
	../load-dir/tailf-ned-cisco-ios-xr-meta.fxs
	$(NCSC) $(JFLAGS) $@ $<

ncsc-out/modules/fxs/%.fxs: yang/%.yang
	$(NCSC) --ncs-compile-module $<          \
	    --ncs-device-dir ncsc-out            \
	    --ncs-skip-statistics                \
	    --ncs-device-type cli-ned            \
	    $(NED_ID_ARG)
	cp $@ ../load-dir

ncsc-out/modules/fxs/%-stats.fxs: yang/%-stats.yang
	$(NCSC) --ncs-compile-module $<          \
	    --ncs-skip-config                    \
	    --ncs-skip-template                  \
	    --ncs-device-dir ncsc-out            \
	    --ncs-device-type cli-ned            \
	    $(NED_ID_ARG)
	cp $@ ../load-dir

../load-dir/%.fxs: yang/%.yang
	$(NCSC) --yangpath yang -c $< -o $@

javac: namespaces
	cd java && ant -q all

clean:
	rm -f ../package-meta-data.xml
	rm -rf ncsc-out/* ../load-dir/* ncsc-out/*
	rm -f ../jar/*.jar
	rm -f java/src/$(JDIR)/$(NS)/*.java
	cd java && ant clean
	cd ../netsim && $(MAKE) clean
