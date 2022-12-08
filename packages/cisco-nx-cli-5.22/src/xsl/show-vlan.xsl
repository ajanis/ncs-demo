<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output indent="yes"/>
  <xsl:template match="/">
    <vlan xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates xmlns:nxos="http://www.cisco.com/nxos:1.0:vlan_mgr_cli" xmlns:nf="urn:ietf:params:xml:ns:netconf:base:1.0" select="nf:rpc-reply/nf:data/nxos:show/nxos:vlan//nxos:__readonly__"/>
    </vlan>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:vlan_mgr_cli" match="nxos:__readonly__">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'TABLE_')]">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'ROW_')]">
    <vlans>
      <xsl:apply-templates/>
    </vlans>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'vlanshowbr-')]">
    <xsl:element name="{substring(local-name(), 12)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'vlanshowinfo-')]">
    <xsl:element name="{substring(local-name(), 14)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:vlan_mgr_cli" match="nxos:vlanshowplist-ifidx">
    <xsl:apply-templates select="@* | node()"/>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:vlan_mgr_cli" match="nxos:vlanshowplist-ifidx/text()" name="tokenize">
    <xsl:param name="text" select="."/>
    <xsl:param name="separator" select="','"/>
    <xsl:choose>
      <xsl:when test="not(contains($text, $separator))">
        <ports>
          <xsl:value-of select="normalize-space($text)"/>
        </ports>
      </xsl:when>
      <xsl:otherwise>
        <ports>
          <xsl:value-of select="normalize-space(substring-before($text, $separator))"/>
        </ports>
        <xsl:call-template name="tokenize">
          <xsl:with-param name="text" select="substring-after($text, $separator)"/>
        </xsl:call-template>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <xsl:template match="*">
    <xsl:element name="{local-name(.)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
</xsl:stylesheet>
