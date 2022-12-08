<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output indent="yes"/>
  <xsl:template match="/">
    <counters xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates xmlns:nxos="http://www.cisco.com/nxos:1.0:nve" xmlns:nf="urn:ietf:params:xml:ns:netconf:base:1.0" select="nf:rpc-reply/nf:data/nxos:show/nxos:nve/nxos:vni/nxos:vni-id/nxos:counters/nxos:__XML__OPT_Cmd_nve_show_vni_counters_cmd___readonly__/nxos:__readonly__"/>
    </counters>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:nve" match="nxos:__readonly__">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:nve" match="nxos:vni"/>
  <xsl:template match="*[starts-with(local-name(),'tx_') or starts-with(local-name(),'rx_')]">
    <xsl:choose>
      <xsl:when test="contains(local-name(), 'ucastpkts')">
	<xsl:element name="{substring(local-name(), 1, 2)}-unicast-packets">
	  <xsl:apply-templates/>
	</xsl:element>
      </xsl:when>
      <xsl:when test="contains(local-name(), 'ucastbytes')">
	<xsl:element name="{substring(local-name(), 1, 2)}-unicast-bytes">
	  <xsl:apply-templates/>
	</xsl:element>
      </xsl:when>
      <xsl:when test="contains(local-name(), 'mcastpkts')">
	<xsl:element name="{substring(local-name(), 1, 2)}-multicast-packets">
	  <xsl:apply-templates/>
	</xsl:element>
      </xsl:when>
      <xsl:otherwise>
	<xsl:element name="{substring(local-name(), 1, 2)}-multicast-bytes">
	  <xsl:apply-templates/>
	</xsl:element>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <xsl:template match="*">
    <xsl:element name="{local-name(.)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
</xsl:stylesheet>
