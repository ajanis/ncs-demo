FXS_WERR= --fail-on-warnings -w TAILF_SUGGEST_COMBO -W TAILF_DIFF_DEP_CIRCULAR_DEPENDENCY -w TAILF_MISSING_REQUIRED_STMT -w TAILF_BAD_COMBO
include $(NCS_DIR)/src/ncs/build/include.netsim.mk


CONFD_FLAGS = --addloadpath $(CONFD_DIR)/etc/confd
START_FLAGS ?=

SHELL = /bin/bash
XML = $(wildcard *.xml)
XML += $(NCS_DIR)/netsim/confd/var/confd/cdb/aaa_init.xml
YANG = tailf-ned-cisco-ios-xr.yang
FXS = $(YANG:%.yang=%.fxs)
FILES = confd.conf.netsim confd.c.ccl confd.i.ccl show_version.sh exec_command.sh

FXS += aaa.fxs ietf-netconf-acm.fxs

NACMDIR = $(NCS_DIR)/netsim/confd/src/confd/yang


.PHONY: all
all: $(FXS) confd.c.ccl confd.i.ccl

aaa.fxs: $(NCS_DIR)/netsim/confd/src/confd/aaa/tailf-aaa.yang
	$(CONFDC) -c --export none -o $@ $<

ietf-netconf-acm.fxs: $(NACMDIR)/ietf-netconf-acm.yang
	$(CONFDC) -c --export none -a $(NACMDIR)/ietf-netconf-acm-ann.yang\
	         -o $@ $<

%.fxs:	../src/tmp-yang/%.yang
	$(CONFDC) $(FXS_WERR) $(EXTRA_LINK_FLAGS) --yangpath ../src/tmp-yang -c -o $@  $<

## Invoked by netsim, who will sed substitute the %var% variables
## in the .netsim files
install:
	@if [ -z "$${DEST_DIR}" ]; then echo "No DEST_DIR  var"; exit 1; fi
	@if [ ! -d "$${DEST_DIR}" ]; then "echo No DEST_DIR"; exit 1; fi
	[ -d $${DEST_DIR}/cdb ]  || mkdir $${DEST_DIR}/cdb
	[ -d $${DEST_DIR}/logs ] || mkdir $${DEST_DIR}/logs
	cp -f $(XML) $${DEST_DIR}/cdb
	cp -f $(FXS) $${DEST_DIR}
	cp -f $(FILES) $${DEST_DIR}
	cp -af $(CONFD_DIR)/etc/confd/ssh $${DEST_DIR}

clean:
	@rm -f *.ccl *.fxs
	@rm -rf netsim

local-netsim: all
	$(NCS_DIR)/bin/ncs-netsim create-network `pwd`/.. 1 a --dir netsim
