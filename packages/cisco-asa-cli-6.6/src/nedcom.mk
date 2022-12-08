SHELL = /bin/bash

NCSCVER := $(shell ncsc --version 2> /dev/null)

# Temporary yang.hrl fix until available in all NSO distributions
YANGERDIST_DIR := $(wildcard $(NCS_DIR)/erlang/yanger)
YANGERSRC_DIR := $(wildcard $(NCS_DIR)/../lib/yanger)

ifeq ($(YANGERDIST_DIR),)
 ifeq ($(YANGERSRC_DIR),)
  YANGER_INC_PATH = schema/$(NCSCVER)
 else
  YANGER_INC_PATH = $(NCS_DIR)/../lib
 endif
else
 YANGER_INC_PATH = $(NCS_DIR)/erlang
endif

# YANG preprocessor
YPP ?= tools/ypp

# Check what to build
SCHEMA_JSON        := $(if $(wildcard schema/jsondump.erl),schema-json)
NETSIM_BUILD       := $(if $(wildcard ../netsim/Makefile),netsim)
NETSIM_CLEAN       := $(if $(NETSIM_BUILD),netsim-clean)
FILTER_YANG_NETSIM := $(if $(NETSIM_BUILD),filter-yang-netsim)

# Java section
NS = namespaces
JAVA_PACKAGE = com.tailf.packages.ned.$(PACKAGE_NAME)
JDIR := $(subst .,/,$(JAVA_PACKAGE))
JFLAGS = --java-disable-prefix \
         --exclude-enums \
         --fail-on-warnings \
         --java-package $(JAVA_PACKAGE).$(NS) \
         --emit-java

# Handle old style YANG modules
ifneq ($(MAIN_YANG_MODULE)$(EXTRA_YANG_MODULES),)
 YANG_CONFIG = $(MAIN_YANG_MODULE) $(EXTRA_YANG_MODULES)
endif

# Define different sets of yang modules
YANG_YANG   := $(wildcard yang/*.yang)
YANG        := $(filter-out cliparser-extensions-%.yang,$(YANG_YANG:yang/%.yang=%.yang))
YANG_STATS  += $(filter %-stats.yang ietf-%.yang,$(YANG))
YANG_OTHER  += $(filter %-meta.yang %-oper.yang %-secrets.yang %-loginscripts.yang,$(YANG))
YANG_CONFIG ?= $(filter-out $(YANG_STATS) $(YANG_OTHER),$(YANG))

ifneq ($(VERBOSE),)
 $(warning YANG        = $(YANG))
 $(warning YANG_CONFIG = $(YANG_CONFIG))
 $(warning YANG_STATS  = $(YANG_STATS))
 $(warning YANG_OTHER  = $(YANG_OTHER))
endif

# NED ID
NED_ID_ARG := $(shell [ -x $(NCS_DIR)/support/ned-ncs-ned-id-arg ] && \
              $(NCS_DIR)/support/ned-ncs-ned-id-arg package-meta-data.xml.in)
ifeq ($(NED_ID_ARG),)
 YANG_ID_MODULE ?= $(patsubst %.yang,%,$(wildcard *-id.yang))
else
 YANG_ID_MODULE ?= tailf-ned-id-$(word 2,$(subst :, ,$(NED_ID_ARG)))
endif

# Conversion functions for YANG paths
y        = $(1:%.yang=yang/%.yang)
ty       = $(1:%.yang=tmp-yang/%.yang)
y2ty     = $(1:yang/%.yang=tmp-yang/%.yang)
ncsc_out = $(1:%.yang=ncsc-out/modules/fxs/%.fxs)

# Cleaner actions
nedcom_cleaner = rm -rf artefacts \
	         && rm -rf tmp-yang \
	         && rm -f *.fxs

# Printer
SAY = @echo $$'\n'========

# Include standard NCS examples build definitions and rules
include $(NCS_DIR)/src/ncs/build/include.ncs.mk
# Include YANG filters
include ned-yang-filter.mk

# Main cli recipe
all_cli: ned-type-cli \
	mkdirs \
	../package-meta-data.xml \
	filter-yang-ned \
	$(SCHEMA_JSON) \
	filter-yang-nso \
	fxs \
	javac \
	$(FILTER_YANG_NETSIM) \
	$(NETSIM_BUILD) \
	nedcom-tidy

# Main generic recipe
all_gen: ned-type-generic \
	mkdirs \
	../package-meta-data.xml \
	filter-yang-ned \
	$(SCHEMA_JSON) \
	filter-yang-nso \
	fxs \
	javac \
	$(FILTER_YANG_NETSIM) \
	$(NETSIM_BUILD) \
	nedcom-tidy
.PHONY: all_gen

# Set NED type
ned-type-%:
	$(eval NED_TYPE := $*-ned)
.PHONY: ned_type

# FIXME -- required?
mkdirs:
	mkdir -p \
	    artefacts \
	    java/src/$(JDIR)/$(NS) \
	    ncsc-out/modules \
	    ../load-dir \
	    ../private-jar \
	    ../shared-jar
.PHONY: mkdirs

# Create package meta-data and ID module
../package-meta-data.xml: package-meta-data.xml.in
	$(SAY) CREATE $@ and ../load-dir/$(YANG_ID_MODULE).fxs
	rm -f $@
	@if [ -x $(NCS_DIR)/support/ned-make-package-meta-data ]; then \
	    $(NCS_DIR)/support/ned-make-package-meta-data $<; \
	    echo "NOTE: $(YANG_ID_MODULE) implicitly created"; \
	else \
	    cp $< $@; \
	    $(NCSC) -c $(YANG_ID_MODULE).yang \
	            -o ../load-dir/$(YANG_ID_MODULE).fxs; \
	fi
	chmod +w $@

# Create json schema dump
schema-json: $(YANG_CONFIG:%.yang=artefacts/%.json) \
	     artefacts/cliparser-extensions-v11.json

artefacts/%.json: yang/%.yang schema/$(NCSCVER)/jsondump.beam
	$(SAY) CREATE $@
	mkdir -p $(dir $@)
	$(NCS_DIR)/bin/yanger -W none -P ./schema/$(NCSCVER) -f json -p tmp-yang/ -o $@ $(call y2ty,$<)

schema/$(NCSCVER)/jsondump.beam: schema/jsondump.erl $(YANGER_INC_PATH)
	@mkdir -p $(dir $@)
	erlc -o $(dir $@) -I $(YANGER_INC_PATH) schema/jsondump.erl

schema/$(NCSCVER):
	@(cd schema/ ; tar -xf yanghrl.tgz $(NCSCVER) 2> /dev/null)

# Filter out cliparser extensions to avoid versioning trouble
filter-yang-nso:
	$(SAY) FILTER OUT CLIPARSER EXTENSIONS FROM YANG MODELS
	$(YPP) $(foreach c,$(CAPABILITIES), --var $(c)=$($(c))) \
	    --from='^.*import cliparser[^\}]+\}' --to='' \
	    --from='^\s+cli:[a-z0-9\-]+(([ \t]+((\"[^\"]+\")|([^\"\{]\S+))\s*)?((;)|(\s*\{[^\}]+\}))|(.*;))' --to='' \
	    'tmp-yang/*.yang'
.PHONY: filter-yang-nso

# FXS files
fxs: tmp-yang/fxs-config $(if $(YANG_STATS),tmp-yang/fxs-stats) $(call ncsc_out,$(YANG_OTHER))
.PHONY: fxs

ncsc-out/modules/fxs:
	@mkdir -p $@

# YANG config, compiled as bundle
tmp-yang/fxs-config: $(call y,$(YANG_CONFIG))
	$(SAY) CREATE $(call ncsc_out,$(YANG_CONFIG))
	mkdir -p tmp-yang/config
	cp $(call ty,$(YANG_CONFIG)) tmp-yang/config
	$(NCSC) --ncs-compile-bundle tmp-yang/config \
	        --ncs-skip-statistics \
	        --ncs-device-dir ncsc-out \
	        --ncs-device-type $(NED_TYPE) \
	        --yangpath tmp-yang/config \
	        --yangpath ncsc-out/modules/yang \
	        $(NED_ID_ARG) \
	        $(SUPPRESS_WARN)
	cp $(call ncsc_out,$(YANG_CONFIG)) ../load-dir
	touch $@

# YANG stats, compiled as bundle
tmp-yang/fxs-stats: $(call y,$(YANG_STATS))
	$(SAY) CREATE $(call ncsc_out,$(YANG_STATS))
	mkdir -p tmp-yang/stats
	cp $(call ty,$(YANG_STATS)) tmp-yang/stats
	$(NCSC) --ncs-compile-bundle tmp-yang/stats \
		  --ncs-skip-config \
		  --ncs-skip-template \
		  --ncs-device-dir ncsc-out \
		  --ncs-device-type $(NED_TYPE) \
		  --yangpath tmp-yang/stats \
		  --yangpath ncsc-out/modules/yang \
		  $(NED_ID_ARG) \
		  $(SUPPRESS_WARN)
	cp $(call ncsc_out,$(YANG_STATS)) ../load-dir
	touch $@

# Other YANG files, compiled as modules
$(call ncsc_out,$(YANG_OTHER)): ncsc-out/modules/fxs/%.fxs: yang/%.yang
	$(SAY) CREATE $@
	$(NCSC) -c $(call y2ty,$<) -o $@ \
	        --yangpath tmp-yang \
	        --yangpath ncsc-out/modules/yang
	cp $@ ../load-dir

# Java stuff
javac: namespace-classes
	$(SAY) COMPILE JAVA
	cd java && ant -q -Dpackage.name=$(PACKAGE_NAME) -Dpackage.dir=$(JDIR) all
.PHONY: javac

# Namespace classes
namespace-classes: \
	say-namespace-classes \
	$(patsubst %.yang,java/src/$(JDIR)/$(NS)/%.java,$(YANG_CONFIG) $(YANG_STATS))
.PHONY: namespace-classes

say-namespace-classes:
	$(SAY) CREATE NAMESPACE CLASSES
.PHONY: say-namespace-classes

java/src/$(JDIR)/$(NS)/%.java: yang/%.yang
	$(NCSC) $(JFLAGS) $@ $(<:yang/%.yang=ncsc-out/modules/fxs/%.fxs)

# Prepare YANG files for netsim
filter-yang-netsim:
	$(SAY) PREPARE YANG MODELS FOR NETSIM
	$(YPP) --var NETSIM=YES $(foreach c,$(CAPABILITIES), --var $(c)=$($(c))) \
	    --from='tailf:callpoint\s+[\w\-]+\s*(;|\{[^\}]*\})' --to='' \
	    --from='//NETSIM' --to='        ' \
	    'tmp-yang/*.yang'
.PHONY: filter-yang-netsim

# Netsim
netsim:
	$(SAY) MAKE netsim
	$(MAKE) -C ../netsim all
.PHONY: netsim

# Cleanup stuff
clean: nedcom-clean $(NETSIM_CLEAN)
	$(SAY) MAKE CLEAN
	rm -rf schema/ncs-*
	rm -rf schema/confd-*
	rm -rf ncsc-out/* ../load-dir/*
	rm -f ../package-meta-data.xml
	rm -f ../private-jar/$(PACKAGE_NAME).jar
	rm -f ../shared-jar/$(PACKAGE_NAME)-ns.jar
	rm -f java/src/$(JDIR)/$(NS)/*.java
	cd java && ant clean
.PHONY: clean

netsim-clean:
	$(MAKE) -C ../netsim clean
.PHONY: netsim-clean

nedcom-clean:
	$(nedcom_cleaner)
.PHONY: nedcom-clean

nedcom-tidy:
	$(SAY) MAKE TIDY
	rm -f ../load-dir/cliparser-extensions-v11.fxs
	@if [ "$(KEEP_FXS)" = "" -a "$$(whoami)" = "jenkins" ] ; then \
	    $(nedcom_cleaner) ; \
	fi
.PHONY: nedcom-tidy
