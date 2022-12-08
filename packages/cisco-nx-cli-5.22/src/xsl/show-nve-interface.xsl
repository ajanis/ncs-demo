<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output indent="yes"/>
  <xsl:template match="/">
    <nve xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates xmlns:nxos="http://www.cisco.com/nxos:1.0:nve" xmlns:nf="urn:ietf:params:xml:ns:netconf:base:1.0" select="nf:rpc-reply/nf:data/nxos:show/nxos:nve/nxos:interface/nxos:__XML__OPT_Cmd_nve_show_nve_cmd_nve-if/nxos:__XML__OPT_Cmd_nve_show_nve_cmd___readonly__/nxos:__readonly__/nxos:TABLE_nve_if/nxos:ROW_nve_if"/>
    </nve>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:nve" match="nxos:ROW_nve_if">
    <interface>
      <xsl:apply-templates/>
    </interface>
  </xsl:template>
  <xsl:template match="*">
    <xsl:element name="{local-name(.)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
</xsl:stylesheet>
