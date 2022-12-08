<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output indent="yes"/>
  <xsl:template match="/">
    <mac xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates xmlns:nxos="http://www.cisco.com/nxos:1.0:l2fm" xmlns:nf="urn:ietf:params:xml:ns:netconf:base:1.0" select="nf:rpc-reply/nf:data/nxos:show/nxos:mac/nxos:address-table/nxos:__XML__OPT_Cmd_show_mac_addr_tbl_static/nxos:__XML__OPT_Cmd_show_mac_addr_tbl_local/nxos:__XML__OPT_Cmd_show_mac_addr_tbl_address/nxos:__XML__OPT_Cmd_show_mac_addr_tbl___readonly__/nxos:__readonly__"/>
    </mac>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:l2fm" match="nxos:__readonly__">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:l2fm" match="nxos:header"/>
  <xsl:template match="*[starts-with(local-name(),'TABLE_')]">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:l2fm" match="nxos:ROW_mac_address">
    <address-table xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates select="@* | node()"/>
    </address-table>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'disp_')]">
    <xsl:choose>
      <xsl:when test="contains(local-name(), 'mac_addr')">
	<mac-address>
	  <xsl:apply-templates/>
	</mac-address>
      </xsl:when>
      <xsl:when test="starts-with(local-name(), 'disp_is_')">
	<xsl:element name="{substring(local-name(), 9)}">
	  <xsl:apply-templates select="@* | node()"/>
	</xsl:element>
      </xsl:when>
      <xsl:otherwise>
	<xsl:element name="{substring(local-name(), 6)}">
	  <xsl:apply-templates select="@* | node()"/>
	</xsl:element>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <xsl:template match="*">
    <xsl:element name="{local-name(.)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
</xsl:stylesheet>
