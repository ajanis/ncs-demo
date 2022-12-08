<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output indent="yes"/>
  <xsl:template match="/">
    <forwarding xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates xmlns:nxos="http://www.cisco.com/nxos:1.0:l3vm" xmlns:nf="urn:ietf:params:xml:ns:netconf:base:1.0" select="nf:rpc-reply/nf:data/nxos:show/nxos:vrf/nxos:__XML__OPT_Cmd_l3vm_show_vrf_cmd_vrf-name/nxos:__XML__OPT_Cmd_l3vm_show_vrf_cmd_detail/nxos:__XML__OPT_Cmd_l3vm_show_vrf_cmd___readonly__/nxos:__readonly__/nxos:TABLE_vrf"/>
    </forwarding>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'TABLE_')]">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'ROW_') or starts-with(local-name(),'vrf_')]">
    <xsl:element name="{substring(local-name(), 5)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
  <xsl:template match="*">
    <xsl:element name="{local-name(.)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
</xsl:stylesheet>
