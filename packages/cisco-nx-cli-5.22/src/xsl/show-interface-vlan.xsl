<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output indent="yes"/>
  <xsl:template xmlns:nx="http://tail-f.com/ned/cisco-nx" match="//nx:Vlan">
    <vlan xmlns="http://tail-f.com/ned/cisco-nx/stats">
      <xsl:apply-templates select="@* | node()"/>
    </vlan>
  </xsl:template>
  <xsl:template match="*">
    <xsl:element name="{local-name(.)}">
      <xsl:apply-templates select="@* | node()"/>
    </xsl:element>
  </xsl:template>
</xsl:stylesheet>

