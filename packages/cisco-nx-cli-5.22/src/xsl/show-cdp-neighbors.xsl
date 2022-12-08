<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output indent="yes"/>
  <xsl:template match="/">
    <cdp xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates xmlns:nxos="http://www.cisco.com/nxos:1.0:cdpd" xmlns:nf="urn:ietf:params:xml:ns:netconf:base:1.0" select="nf:rpc-reply/nf:data/nxos:show/nxos:cdp/nxos:neighbors/nxos:__XML__OPT_Cmd_show_cdp_neighbors_interface/nxos:__XML__OPT_Cmd_show_cdp_neighbors___readonly__/nxos:__readonly__"/>
    </cdp>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:cdpd" match="nxos:__readonly__">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:cdpd" match="nxos:neigh_count"/>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:cdpd" match="nxos:TABLE_cdp_neighbor_brief_info">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:cdpd" match="nxos:ROW_cdp_neighbor_brief_info">
    <neighbors>
      <xsl:apply-templates/>
    </neighbors>
  </xsl:template>
  <xsl:template match="*">
    <xsl:element name="{translate(local-name(.), '_', '-')}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
</xsl:stylesheet>
