
JAVA_PACKAGE = com.tailf.packages.ned.alusr
JDIR = $(shell echo $(JAVA_PACKAGE) | sed 's/\./\//g')

NS  = namespaces

JFLAGS = --java-disable-prefix \
         --exclude-enums \
         --fail-on-warnings \
         --java-package $(JAVA_PACKAGE).$(NS) \
         --emit-java java/src/$(JDIR)/namespaces

YANG_MODULE = tailf-ned-alu-sr
NED_ID_ARG = $(shell [ -x ${NCS_DIR}/support/ned-ncs-ned-id-arg ] && \
             ${NCS_DIR}/support/ned-ncs-ned-id-arg package-meta-data.xml.in)
ifeq ($(NED_ID_ARG),)
NED_ID_FILE = $(YANG_MODULE)-id
else
NED_ID_FILE = tailf-ned-id-$(shell echo $(NED_ID_ARG) | cut -d: -f2)
endif
JAVA_ID_FILE = $(shell echo $(YANG_MODULE) | \
           perl -pe 's/-ned//;s/(^|-)./uc($$&)/ge;s/-//ge;s/(.)/lc($$&)/e')Id

DIRS = ../load-dir ../private-jar ../shared-jar \
	ncsc-out java/src/$(JDIR)/namespaces

all:	$(DIRS) ../package-meta-data.xml ned-id-file fxs javac
	cd ../netsim && $(MAKE) all

$(DIRS):
	mkdir -p $@

# Include standard NCS examples build definitions and rules
include $(NCS_DIR)/src/ncs/build/include.ncs.mk

YANG = $(wildcard yang/*.yang)
SUBMODULES = $(shell grep -l belongs-to yang/*.yang)
YANG_MODULES = $(filter-out $(SUBMODULES),$(YANG))

FXS = $(YANG_MODULES:yang/%.yang=ncsc-out/modules/fxs/%.fxs)

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
	$(NCSC) $(JFLAGS)/$(JAVA_ID_FILE).java ../load-dir/$(NED_ID_FILE).fxs

fxs:	ncsc-out/modules/fxs/tailf-ned-alu-sr.fxs       \
	ncsc-out/modules/fxs/tailf-ned-alu-sr-stats.fxs \
	../load-dir/tailf-ned-alu-sr-meta.fxs
# Generate yang from output from alu's tree command. The real alu.yang is
# based on this output but has manual modifications.  Use a mergetool like
# vimdiff or meld to sync the generated file with alu.yang.
tree: data/alu.yang
data/alu.yang: data/alu.tree
	python tools/tree/compiler.py $< > $@

ncsc-out/modules/fxs/tailf-ned-alu-sr.fxs: yang/tailf-ned-alu-sr.yang
	$(NCSC) --ncs-compile-module $< \
	    --ncs-device-dir ncsc-out   \
	    --ncs-skip-statistics       \
	    --ncs-device-type cli-ned   \
	    $(NED_ID_ARG)
	cp $@ ../load-dir
	$(NCSC) $(JFLAGS)/tailfNedAluSr.java $@

ncsc-out/modules/fxs/tailf-ned-alu-sr-stats.fxs: \
	yang/tailf-ned-alu-sr-stats.yang
	$(NCSC) --ncs-compile-module $< \
	    --ncs-skip-config           \
	    --ncs-device-dir ncsc-out   \
	    --ncs-skip-template         \
	    --ncs-device-type cli-ned   \
	    $(NED_ID_ARG)
	cp $@ ../load-dir

../load-dir/%.fxs:	yang/%.yang
	$(NCSC) --yangpath yang -c $< -o $@

javac:
	cd java && ant -q all

clean:
	rm -f ../package-meta-data.xml
	rm -rf $(DIRS)
	cd java && ant clean
	cd ../netsim && $(MAKE) clean
