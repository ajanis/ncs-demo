<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output indent="yes"/>
  <xsl:template match="/">
    <evpn xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates xmlns:nxos="http://www.cisco.com/nxos:1.0:bgp" xmlns:nf="urn:ietf:params:xml:ns:netconf:base:1.0" select="nf:rpc-reply/nf:data/nxos:show/nxos:bgp//nxos:TABLE_neighbor"/>
    </evpn>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'TABLE_')]">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'ROW_')]">
    <xsl:choose>
      <xsl:when test="contains(local-name(), '_neighbor')">
	<xsl:element name="neighbors">
	  <xsl:apply-templates select="@* | node()"/>
	</xsl:element>
      </xsl:when>
      <xsl:otherwise>
	<xsl:element name="{substring(local-name(), 5)}">
	  <xsl:apply-templates select="@* | node()"/>
	</xsl:element>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'inpolicy')]">
    <xsl:element name="{substring(local-name(), 9)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'outpolicy')]">
    <xsl:element name="{substring(local-name(), 10)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
  <xsl:template match="*">
    <xsl:element name="{local-name(.)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
</xsl:stylesheet>
