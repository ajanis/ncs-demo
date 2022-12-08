#################################################################################
#
# MAKEFILE PLUGIN TO BE USED FOR FILTERING OUT YANG ANNOTATIONS UNSUPPORTED BY
# CERTAIN COMPILERS
#
# NOTE: Original of this file resides in nedcom, don't edit local copy in ned.
#
#################################################################################

NCS_VER := $(shell ($(NCS) --version))
NCS_VER_NUMERIC := $(shell (echo $(firstword $(subst _, ,$(NCS_VER))) \
	           | awk -F. '{ printf("%d%02d%02d%02d\n", $$1%100,$$2%100,$$3%100,$$4%100); }'))
NSO_FEATURES_FILE := $(NCS_DIR)/support/nso-features.txt
NSO_FEATURES := $(shell cut -d ' ' -f 2 $(NSO_FEATURES_FILE))
PROPERTIES_FILE := artefacts/nso-ned-capabilities.properties
YANG_SNIPPETS_DIR := tmp-yang/snippets

NSO_CAPABILITIES = \
	cdm \
	cdm2 \
	transfer-config-as-xml

YANG_CAPABILITIES = \
	ned-data \
	ned-data2 \
	ned-ignore-compare-config \
	ned-default-handling-mode \
	ned-device-platform \
	ned-new-diff-deps \
	ned-suppress-leafref-in-diff


NSO_FILTERS = $(NSO_CAPABILITIES)

YANG_FILTERS = $(YANG_CAPABILITIES)

ifneq ($(VERBOSE),)
$(warning NCS_VER = $(NCS_VER))
$(warning NCS_VER_NUMERIC = $(NCS_VER_NUMERIC))
endif

# YANG changes if CDM is/isn't supported
# FIXME -- Deprecated, use ypp instead to preprocess .yang files
CDM_FILTER_NO = \
	--from='\s*import\s+\S+-(cli|gen)\s*((;)|(\{[^\}]+\}))' \
	--to='' \
	'tmp-yang/*.yang'

CDM_TEST = $(NCSC) -h | grep -q ncs-ned-id

CDM2_FILTER_YES = \
	--from='//(.*when\s*\"derived-from\(.*\)\".*[\}\s;]+)' \
	--to='\1' \
	'tmp-yang/*.yang'
CDM2_FILTER_NO = \
	--from='//(.*when\s*\"\S+ned-id\s*=.*\".*[\}\s;]+)' \
	--to='\1' \
	'tmp-yang/*.yang'

CDM2_TEST = $(CDM_TEST)

# Test if XML config transfer supported
TRANSFER_CONFIG_AS_XML_TEST = [ $(NCS_VER_NUMERIC) -ge 4040100 ]

# Remove ned-data if not supported
NED_DATA_FILTER_NO = \
	--from='(tailf:ned-data\s*\"\S+\"\s+\{\s*[\r\n]\s*)(\S+\s+\S+;[\n\r]\s*)(\})' \
	--to='//\1//\2//\3' \
	'tmp-yang/*.yang'

$(YANG_SNIPPETS_DIR)/ned-data-snippet.yang:
	@echo 'module ned-data-snippet {' > $@
	@echo '  namespace "http://tail-f.com/ned/ned-data";' >> $@
	@echo '  prefix ned-data;' >> $@
	@echo '  import tailf-common {' >> $@
	@echo '    prefix tailf;' >> $@
	@echo '  }' >> $@
	@echo '  leaf foo {' >> $@
	@echo '    tailf:ned-data "." {' >> $@
	@echo '      tailf:transaction both;' >> $@
	@echo '    }' >> $@
	@echo '    type uint32;' >> $@
	@echo '  }' >> $@
	@echo '}' >> $@

# Use requires-transaction-states if ned-data not supported
NED_DATA2_FILTER_YES = \
	--from='(?<!--)(<option>\s*[\r\n]\s*<name>requires-transaction-states</name>\s*[\r\n]\s*</option>)' \
	--to='<!--\1-->' \
	'../package-meta-data.xml'
NED_DATA2_FILTER_NO = \
	--from='<!--(<option>\s*[\r\n]\s*<name>requires-transaction-states</name>\s*[\r\n]\s*</option>)-->' \
	--to='\1' \
	'../package-meta-data.xml'

$(YANG_SNIPPETS_DIR)/ned-data2-snippet.yang: $(YANG_SNIPPETS_DIR)/ned-data-snippet.yang
	cp $< $@

# Remove ignore-compare-config if not supported
NED_IGNORE_COMPARE_CONFIG_FILTER_NO = \
	--from='(tailf:ned-ignore-compare-config;)' \
	--to='//\1' \
	'tmp-yang/*.yang'

$(YANG_SNIPPETS_DIR)/ned-ignore-compare-config-snippet.yang:
	@echo 'module ned-ignore-compare-config-snippet {' > $@
	@echo '  namespace "http://tail-f.com/ned/ned-ignore-compare-config";' >> $@
	@echo '  prefix ned-ignore-compare-config;' >> $@
	@echo '  import tailf-common {' >> $@
	@echo '    prefix tailf;' >> $@
	@echo '  }' >> $@
	@echo '  leaf foo {' >> $@
	@echo '    tailf:ned-ignore-compare-config;' >> $@
	@echo '    type uint32;' >> $@
	@echo '  }' >> $@
	@echo '}' >> $@

# Remove ned-default-handling if not supported
NED_DEFAULT_HANDLING_MODE_FILTER_NO = \
	--from='(tailf:ned-default-handling|tailf:cli-trim-default)' \
	--to='//\1' \
	'tmp-yang/*.yang'

$(YANG_SNIPPETS_DIR)/ned-default-handling-mode-snippet.yang:
	@echo 'module ned-default-handling-mode-snippet {' > $@
	@echo '  namespace "http://tail-f.com/ned/ned-default-handling-mode-snippet";' >> $@
	@echo '  prefix ned-default-handling-mode-snippet;' >> $@
	@echo '  import tailf-common {' >> $@
	@echo '    prefix tailf;' >> $@
	@echo '  }' >> $@
	@echo '  leaf foo {' >> $@
	@echo '    tailf:ned-default-handling trim;' >> $@
	@echo '    type uint32;' >> $@
	@echo '    default 0;' >> $@
	@echo '  }' >> $@
	@echo '}' >> $@

# Remove devices device platform if not supported
NED_DEVICE_PLATFORM_FILTER_NO = \
	--from='\s*augment \"/ncs:devices/ncs:device/ncs:platform.*' \
	--to='' \
	'tmp-yang/*.yang'

$(YANG_SNIPPETS_DIR)/ned-device-platform-snippet.yang:
	@echo 'module ned-device-platform-snippet {' > $@
	@echo '  namespace "http://tail-f.com/ned/ned-device-platform-snippet";' >> $@
	@echo '  prefix ned-device-platform-snippet;' >> $@
	@echo '  import tailf-common {' >> $@
	@echo '    prefix tailf;' >> $@
	@echo '  }' >> $@
	@echo '  import tailf-ncs {' >> $@
	@echo '    prefix ncs;' >> $@
	@echo '  }' >> $@
	@echo '  augment "/ncs:devices/ncs:device/ncs:platform" {' >> $@
	@echo '   leaf dummy { type string; }' >> $@
	@echo '  }' >> $@
	@echo '}' >> $@

# Remove new diff-deps if not supported
NED_NEW_DIFF_DEPS_FILTER_NO = \
	--from='tailf:cli-diff-(delete|modify|create|set|after|before)(-\S+)?\s+\"\S+\"\s*((;)|(\{[^\}]+\}))' \
	--to='' \
	'tmp-yang/*.yang'

$(YANG_SNIPPETS_DIR)/ned-new-diff-deps-snippet.yang:
	@echo 'module ned-new-diff-deps-snippet {' > $@
	@echo ' namespace "http://tail-f.com/ned/ned-clidiff";' >> $@
	@echo ' prefix ned-clidiff;' >> $@
	@echo ' import tailf-common {' >> $@
	@echo '   prefix tailf;' >> $@
	@echo ' }' >> $@
	@echo ' leaf foo {' >> $@
	@echo '   type uint32;' >> $@
	@echo ' }' >> $@
	@echo ' leaf bar {' >> $@
	@echo '   tailf:cli-diff-delete-before "../foo";' >> $@
	@echo '   type uint32;' >> $@
	@echo ' }' >> $@
	@echo '}' >> $@

# Remove ned-default-handling if not supported
NED_SUPPRESS_LEAFREF_IN_DIFF_FILTER_NO = \
	--from='(tailf:cli-suppress-leafref-in-diff)' \
	--to='//\1' \
	'tmp-yang/*.yang'

$(YANG_SNIPPETS_DIR)/ned-suppress-leafref-in-diff-snippet.yang:
	@echo 'module ned-suppress-leafref-in-diff-snippet {' > $@
	@echo '  namespace "http://tail-f.com/ned/ned-suppress-leafref-in-diff-snippet";' >> $@
	@echo '  prefix ned-suppress-leafref-in-diff-snippet;' >> $@
	@echo '  import tailf-common {' >> $@
	@echo '    prefix tailf;' >> $@
	@echo '  }' >> $@
	@echo '  leaf foo {' >> $@
	@echo '    tailf:non-strict-leafref {' >> $@
	@echo '      path "../bar";' >> $@
	@echo '    }' >> $@
	@echo '    tailf:cli-suppress-leafref-in-diff;' >> $@
	@echo '    type uint32;' >> $@
	@echo '  }' >> $@
	@echo '  leaf bar {' >> $@
	@echo '    type uint32;' >> $@
	@echo '  }' >> $@
	@echo '}' >> $@


$(YANG_SNIPPETS_DIR):
	rm -rf $(YANG_SNIPPETS_DIR)
	mkdir -p $(YANG_SNIPPETS_DIR)
.PHONY: $(YANG_SNIPPETS_DIR)

$(PROPERTIES_FILE):
	$(SAY) CREATE $@
	mkdir -p artefacts
	rm -f $@
	echo "# Property file auto generated for NSO $(NCS_VER)" > $@
	@echo "nso-version=$(NCS_VER)" >> $@
	@echo "nso-version-numeric=$(NCS_VER_NUMERIC)" >> $@
	$(eval CAPABILITIES := NCS_VER NCS_VER_NUMERIC)
.PHONY: $(PROPERTIES_FILE)

features:
	$(SAY) CHECK FOR NSO FEATURES
	@$(foreach f,$(NSO_FEATURES),\
	  $(eval CAPABILITY := $(shell echo $(f) | tr a-z\- A-Z_)) \
	  $(eval CAPABILITIES := $(CAPABILITIES) SUPPORTS_$(CAPABILITY)) \
	  $(eval SUPPORTS_$(CAPABILITY) := YES) \
	  echo "NSO HAS FEATURE $(f)"; \
	  echo "$(f)=yes" >> $(PROPERTIES_FILE); \
	  echo "supports-$(f)=yes" >> $(PROPERTIES_FILE); \
	)
.PHONY: features

nso-capabilities:
	$(SAY) CHECK FOR NSO CAPABILITIES
	@$(foreach f,$(NSO_CAPABILITIES),\
	  $(eval CAPABILITY := $(shell echo $(f) | tr a-z\- A-Z_)) \
	  $(eval CAPABILITIES := $(CAPABILITIES) SUPPORTS_$(CAPABILITY)) \
	  $(eval SUPPORTS_$(CAPABILITY) := $(shell $($(CAPABILITY)_TEST) > /dev/null 2>&1 && echo YES || echo NO)) \
	  $(eval $(CAPABILITY)_FILTER := $($(CAPABILITY)_FILTER_$(SUPPORTS_$(CAPABILITY)))) \
	  echo "NSO $(if $(filter YES,$(SUPPORTS_$(CAPABILITY))),SUPPORTS,DOES NOT SUPPORT) $(f)"; \
	  echo "supports-$(f)=$(if $(filter YES,$(SUPPORTS_$(CAPABILITY))),yes,no)" >> $(PROPERTIES_FILE); \
        )

yang-snippets: $(patsubst %,$(YANG_SNIPPETS_DIR)/%-snippet.yang,$(YANG_CAPABILITIES))
.PHONY: yang-snippets

yang-capabilities: yang-snippets
	$(SAY) CHECK FOR NSO YANG CAPABILITIES
	@$(foreach f,$(YANG_CAPABILITIES),\
	  $(eval CAPABILITY := $(shell echo $(f) | tr a-z\- A-Z_)) \
	  $(eval CAPABILITIES := $(CAPABILITIES) SUPPORTS_$(CAPABILITY)) \
	  $(eval SUPPORTS_$(CAPABILITY) := $(shell $(NCSC) --yangpath yang \
	                                   -c $(YANG_SNIPPETS_DIR)/$(f)-snippet.yang \
	                                   -o $(YANG_SNIPPETS_DIR)/$(f)-snippet.fxs \
	                                    > /dev/null 2>&1 && echo YES || echo NO)) \
	  $(eval $(CAPABILITY)_FILTER := $($(CAPABILITY)_FILTER_$(SUPPORTS_$(CAPABILITY)))) \
	  echo "YANG COMPILER $(if $(filter YES,$(SUPPORTS_$(CAPABILITY))),SUPPORTS,DOES NOT SUPPORT) $(f)"; \
	  echo "supports-$(f)=$(if $(filter YES,$(SUPPORTS_$(CAPABILITY))),yes,no)" >> $(PROPERTIES_FILE); \
        )

capabilities: nso-capabilities yang-capabilities
.PHONY: capabilities

filters:
	$(SAY) APPLY YANG FILTERS
	$(foreach f, $(NSO_FILTERS) $(YANG_FILTERS),\
	  $(eval CAPABILITY := $(shell echo $(f) | tr a-z\- A-Z_)) \
	  $(YPP) $(foreach c,$(CAPABILITIES), --var $(c)=$($(c))) $($(CAPABILITY)_FILTER); \
	)
.PHONY: filters

NEDCOM_SECRET_TYPE ?= string
process-ypp-vars:
	$(SAY) "RUNNING YANG PRE-PROCESSOR (YPP) WITH THE FOLLOWING VARIABLES:"
	@echo "  NEDCOM_SECRET_TYPE=$(NEDCOM_SECRET_TYPE)"
	$(foreach v,$(YPP_VARS), @echo "  $(v)=$($(v))";)
	@echo ""
	$(YPP) --from=' NEDCOM_SECRET_TYPE' \
	--to=' $(NEDCOM_SECRET_TYPE)' \
	$(foreach v,$(YPP_VARS),--var $(v)=$($(v))) \
	'tmp-yang/*.yang'
	@echo "nedcom-secret-type=$(NEDCOM_SECRET_TYPE)" >> $(PROPERTIES_FILE);
.PHONY: process-ypp-vars

filter-yang-ned: \
	$(YANG_SNIPPETS_DIR) \
	$(PROPERTIES_FILE) \
	features \
	capabilities \
	filters \
	process-ypp-vars
