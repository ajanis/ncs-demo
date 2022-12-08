<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output indent="yes"/>
  <xsl:template match="/">
    <inventory xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates xmlns:nxos="http://www.cisco.com/nxos:1.0:pfm" xmlns:nf="urn:ietf:params:xml:ns:netconf:base:1.0" select="nf:rpc-reply/nf:data/nxos:show/nxos:inventory/nxos:__XML__OPT_Cmd_show_inv_chassis/nxos:__XML__OPT_Cmd_show_inv___readonly__/nxos:__readonly__"/>
    </inventory>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:pfm" match="nxos:__readonly__">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:pfm" match="nxos:TABLE_inv">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:pfm" match="nxos:ROW_inv">
    <inventory>
      <xsl:apply-templates/>
    </inventory>
  </xsl:template>
  <xsl:template match="*">
    <xsl:element name="{translate(local-name(.), '_', '-')}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
  <xsl:template match="*/text()">
    <xsl:value-of select="translate(., '\&quot;', '')"/>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:pfm" match="nxos:desc">
    <xsl:element name="description">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
    <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:pfm" match="nxos:productid">
    <xsl:element name="product-id">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:pfm" match="nxos:vendorid">
    <xsl:element name="vendor-id">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:pfm" match="nxos:serialnum">
    <xsl:element name="serial">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
 </xsl:stylesheet>
