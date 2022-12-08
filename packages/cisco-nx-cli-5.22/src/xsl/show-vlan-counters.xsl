<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output indent="yes"/>
  <xsl:template match="/">
    <vlan xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates xmlns:nxos="http://www.cisco.com/nxos:1.0:pfstat" xmlns:nf="urn:ietf:params:xml:ns:netconf:base:1.0" select="nf:rpc-reply/nf:data/nxos:show/nxos:vlan//nxos:TABLE_vlancounters"/>
    </vlan>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'TABLE_')]">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:pfstat" match="nxos:ROW_vlancounters">
    <counters>
      <xsl:apply-templates select="@* | node()"/>
    </counters>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:pfstat" match="nxos:vlanshowbr-vlanid">
    <xsl:element name="vlanid">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'l2_ing') and contains(local-name(), '_b')]">
    <xsl:element name="{substring(local-name(),8,5)}-bytes-in">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'l2_ing') and contains(local-name(), '_p')]">
    <xsl:element name="{substring(local-name(),8,5)}-packets-in">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:pfstat" match="nxos:l2_egr_ucast_b">
    <ucast-bytes-out>
      <xsl:apply-templates select="@* | node()"/>
    </ucast-bytes-out>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:pfstat" match="nxos:l2_egr_ucast_p">
    <ucast-packets-out>
      <xsl:apply-templates select="@* | node()"/>
    </ucast-packets-out>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:pfstat" match="nxos:l3_ucast_rcv_b">
    <l3-ucast-bytes-in>
      <xsl:apply-templates select="@* | node()"/>
    </l3-ucast-bytes-in>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:pfstat" match="nxos:l3_ucast_rcv_p">
    <l3-ucast-packets-in>
      <xsl:apply-templates select="@* | node()"/>
    </l3-ucast-packets-in>
  </xsl:template>
  <xsl:template match="*">
    <xsl:element name="{local-name(.)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
</xsl:stylesheet>
