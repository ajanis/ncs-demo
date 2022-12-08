<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output indent="yes"/>
  <xsl:template match="/">
    <ipv4 xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates xmlns:nxos="http://www.cisco.com/nxos:1.0:ipfib" xmlns:nf="urn:ietf:params:xml:ns:netconf:base:1.0" select="nf:rpc-reply/nf:data/nxos:show/nxos:forwarding//nxos:__readonly__/nxos:TABLE_module/nxos:ROW_module/nxos:TABLE_vrf/nxos:ROW_vrf/nxos:TABLE_prefix"/>
    </ipv4>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'TABLE_')]">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'ip_')]">
    <xsl:element name="{substring(local-name(), 4)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:ipfib" match="nxos:ROW_prefix">
    <route>
      <xsl:apply-templates select="@* | node()"/>
    </route>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:ipfib" match="nxos:ROW_path">
    <path>
      <xsl:apply-templates select="@* | node()"/>
    </path>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:ipfib" match="nxos:special">
    <nexthop>
      <xsl:apply-templates select="@* | node()"/>
    </nexthop>
  </xsl:template>
  <xsl:template match="*">
    <xsl:element name="{local-name(.)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
</xsl:stylesheet>
