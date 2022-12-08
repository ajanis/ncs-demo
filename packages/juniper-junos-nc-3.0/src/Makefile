

NCSC_DEVICE_DIR = ./ncsc-out

all: fxs
	$(MAKE) -C ../netsim all
.PHONY: all

# Include standard NCS examples build definitions and rules
include $(NCS_DIR)/src/ncs/build/include.ncs.mk

SRC = yang/junos.yang yang/junos-rpc.yang
NFXS = ncsc-out/modules/fxs
FXS = $(SRC:yang/%.yang=../load-dir/%.fxs)
DIRS = ncsc-out ncsc-out/modules $(NFXS) ../load-dir
NED_ID_ARG = $(shell [ -x ${NCS_DIR}/support/ned-ncs-ned-id-arg ] && \
               ${NCS_DIR}/support/ned-ncs-ned-id-arg package-meta-data.xml.in)

fxs: $(DIRS) ../package-meta-data.xml $(FXS)
.PHONY: fxs

$(DIRS):
	mkdir -p $@

../package-meta-data.xml: package-meta-data.xml.in
	#ned-id built by: support/ned-make-package-meta-data"
	rm -rf $@
	if [ -x ${NCS_DIR}/support/ned-make-package-meta-data ]; then \
	    ${NCS_DIR}/support/ned-make-package-meta-data $<;         \
	else                                                          \
	    cp $< $@;                                                 \
	fi
	chmod -w $@

# Junos doesn't have any Yang config false data, thus
# we can skip the statistics
$(NFXS)/%.fxs: yang/%.yang
	rm -f $@ || true
	mkdir -p $(NCSC_DEVICE_DIR)
	$(NCSC) --use-description                   \
		--ncs-compile-module $<             \
		--ncs-device-dir $(NCSC_DEVICE_DIR) \
		--ncs-skip-statistics               \
		--ncs-device-type netconf           \
		$(NED_ID_ARG) | 2>&1                \
	  grep -v 'has no actionpoint' || true

../load-dir/%.fxs: $(NFXS)/%.fxs
	rm -f $@
	ln -s ../src/$< $@

.PRECIOUS: $(NFXS)/%.fxs

clean:
	rm -rf $(DIRS)
	rm -rf ../package-meta-data.xml
	$(MAKE) -C ../netsim clean
.PHONY: clean
