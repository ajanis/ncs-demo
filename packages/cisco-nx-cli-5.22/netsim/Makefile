
FXS_WERR= -w TAILF_SUGGEST_COMBO
include $(NCS_DIR)/src/ncs/build/include.netsim.mk

CONFD_FLAGS = --addloadpath $(CONFD_DIR)/etc/confd
START_FLAGS ?=

XML = $(wildcard *.xml)
FILES = confd.conf.netsim confd.i.ccl show_version.sh do_ping.sh do_traceroute.sh cmd_dispatcher.sh

XML += $(NCS_DIR)/netsim/confd/var/confd/cdb/aaa_init.xml
FXS += tailf-ned-cisco-nx.fxs aaa.fxs ietf-netconf-acm.fxs

SED=sed
ifeq (Darwin,$(shell uname))
ifeq (,$(shell which gsed))
$(error "Need gnu version of sed, install it as gsed in PATH")
endif
SED = gsed
endif

NACMDIR = $(NCS_DIR)/netsim/confd/src/confd/yang

.PHONY: all
all: $(FXS) confd.i.ccl

aaa.fxs: $(NCS_DIR)/netsim/confd/src/confd/aaa/tailf-aaa.yang
	$(CONFDC) -c --export none -o $@ $<

ietf-netconf-acm.fxs: $(NACMDIR)/ietf-netconf-acm.yang
	$(CONFDC) -c --export none -a $(NACMDIR)/ietf-netconf-acm-ann.yang\
	         -o $@ $<

tailf-ned-cisco-nx.yang: ../src/yang/tailf-ned-cisco-nx.yang
	cp ../src/tmp-yang/tailf-ned-cisco-nx.yang tailf-ned-cisco-nx.yang

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
	@rm -f *.ccl *.fxs *.yang
	@rm -rf netsim


local-netsim: all
	$(NCS_DIR)/bin/ncs-netsim create-network `pwd`/.. 1 a --dir netsim
