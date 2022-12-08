<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output indent="yes"/>
  <xsl:template match="/">
    <xsl:apply-templates xmlns:nxos="http://www.cisco.com/nxos:1.0:if_manager" xmlns:nf="urn:ietf:params:xml:ns:netconf:base:1.0" select="nf:rpc-reply/nf:data/nxos:show/nxos:interface//nxos:ROW_interface"/>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:if_manager" match="nxos:ROW_interface">
    <ethernet>
      <xsl:apply-templates/>
    </ethernet>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:if_manager" match="nxos:interface"/>
  <xsl:template match="*[starts-with(local-name(),'eth_')]">
    <xsl:element name="{translate(substring(local-name(), 5), '_', '-')}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
  <xsl:template match="*[not(starts-with(local-name(),'eth_')) and not(starts-with(local-name(),'ROW_')) and contains(local-name(),'_')]">
    <xsl:element name="{translate(local-name(), '_', '-')}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
  <xsl:template match="*">
    <xsl:element name="{local-name(.)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
</xsl:stylesheet>

