<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output indent="yes"/>
  <xsl:strip-space elements="*"/>
  <xsl:template match="/">
    <summary xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates xmlns:nxos="http://www.cisco.com/nxos:1.0:eth_pcm_dc3" xmlns:nf="urn:ietf:params:xml:ns:netconf:base:1.0" select="nf:rpc-reply/nf:data/nxos:show/nxos:port-channel/nxos:summary/nxos:__XML__OPT_Cmd_show_port_channel_summary_interface/nxos:__XML__OPT_Cmd_show_port_channel_summary___readonly__/nxos:__readonly__"/>
    </summary>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:eth_pcm_dc3" match="nxos:__readonly__">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'TABLE_')]">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'ROW_')]">
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
