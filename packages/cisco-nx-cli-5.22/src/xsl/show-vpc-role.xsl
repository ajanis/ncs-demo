<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output indent="yes"/>
  <xsl:template match="/">
    <role xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates xmlns:nxos="http://www.cisco.com/nxos:1.0:mcecm" xmlns:nf="urn:ietf:params:xml:ns:netconf:base:1.0" select="nf:rpc-reply/nf:data/nxos:show/nxos:vpc/nxos:role/nxos:__XML__OPT_Cmd_show_vpc_role___readonly__/nxos:__readonly__"/>
    </role>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:mcecm" match="nxos:__readonly__">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template match="*">
    <xsl:element name="{local-name(.)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
</xsl:stylesheet>
