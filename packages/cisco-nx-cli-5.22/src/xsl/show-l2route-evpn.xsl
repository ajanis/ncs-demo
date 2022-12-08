<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output indent="yes"/>
  <xsl:template match="/">
    <top xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates xmlns:nxos="http://www.cisco.com/nxos:1.0:l2rib" xmlns:nf="urn:ietf:params:xml:ns:netconf:base:1.0" select="nf:rpc-reply/nf:data/nxos:show/nxos:l2route/nxos:evpn//nxos:__readonly__"/>
    </top>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:l2rib" match="nxos:__readonly__">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'TABLE_')]">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'ROW_')]">
    <all xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates select="@* | node()"/>
    </all>
  </xsl:template>
  <xsl:template match="*">
    <xsl:element name="{translate(local-name(.), '_', '-')}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
</xsl:stylesheet>
