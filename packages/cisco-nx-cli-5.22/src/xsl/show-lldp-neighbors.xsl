<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output indent="yes"/>
  <xsl:template match="/">
    <lldp xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates xmlns:nxos="http://www.cisco.com/nxos:1.0:lldp" xmlns:nf="urn:ietf:params:xml:ns:netconf:base:1.0" select="nf:rpc-reply/nf:data/nxos:show/nxos:lldp/nxos:neighbors/nxos:__XML__OPT_Cmd_lldp_show_neighbors_interface/nxos:__XML__OPT_Cmd_lldp_show_neighbors_detail___readonly__/nxos:__readonly__"/>
    </lldp>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:lldp" match="nxos:__readonly__">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:lldp" match="nxos:neigh_hdr"/>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:lldp" match="nxos:neigh_count"/>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:lldp" match="nxos:TABLE_nbor_detail">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:lldp" match="nxos:ROW_nbor_detail">
    <neighbors>
      <xsl:apply-templates/>
    </neighbors>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:lldp" match="nxos:l_port_id">
    <local-port-id>
      <xsl:apply-templates/>
    </local-port-id>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'sys_')]">
    <xsl:element name="system-{substring(local-name(), 5)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
  <xsl:template match="*">
    <xsl:element name="{translate(local-name(.), '_', '-')}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
</xsl:stylesheet>
