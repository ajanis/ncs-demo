<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output indent="yes"/>
  <xsl:template match="/">
    <summary xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates xmlns:nxos="http://www.cisco.com/nxos:1.0:nve" xmlns:nf="urn:ietf:params:xml:ns:netconf:base:1.0" select="nf:rpc-reply/nf:data/nxos:show/nxos:nve/nxos:vni/nxos:__XML__OPT_Cmd_nve_show_nve_vni_cmd_interface/nxos:__XML__OPT_Cmd_nve_show_nve_vni_cmd___readonly__/nxos:__readonly__/nxos:TABLE_nve_vni/nxos:ROW_nve_vni"/>
    </summary>
  </xsl:template>
  <xsl:template xmlns:nxos="http://www.cisco.com/nxos:1.0:nve" match="nxos:ROW_nve_vni">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'cp-vni-')]">
    <control-plane xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:choose>
	<xsl:when test="contains(local-name(), 'count')">
	  <vnis>
	    <xsl:apply-templates/>
	  </vnis>
	</xsl:when>
	<xsl:otherwise>
	  <xsl:element name="{substring(local-name(), 8)}">
	    <xsl:apply-templates select="@* | node()"/>
	  </xsl:element>
	</xsl:otherwise>
      </xsl:choose>
    </control-plane>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'dp-vni-')]">
    <data-plane xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:choose>
	<xsl:when test="contains(local-name(), 'count')">
	  <vnis>
	    <xsl:apply-templates/>
	  </vnis>
	</xsl:when>
	<xsl:otherwise>
	  <xsl:element name="{substring(local-name(), 8)}">
	    <xsl:apply-templates select="@* | node()"/>
	  </xsl:element>
	</xsl:otherwise>
      </xsl:choose>
    </data-plane>
  </xsl:template>
  <xsl:template match="*[starts-with(local-name(),'uc-vni-')]">
    <unconfigured xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:choose>
	<xsl:when test="contains(local-name(), 'count')">
	  <vnis>
	    <xsl:apply-templates/>
	  </vnis>
	</xsl:when>
	<xsl:otherwise>
	  <xsl:element name="{substring(local-name(), 8)}">
	    <xsl:apply-templates select="@* | node()"/>
	  </xsl:element>
	</xsl:otherwise>
      </xsl:choose>
    </unconfigured>
  </xsl:template>
  <xsl:template match="*">
    <xsl:element name="{local-name(.)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
</xsl:stylesheet>
