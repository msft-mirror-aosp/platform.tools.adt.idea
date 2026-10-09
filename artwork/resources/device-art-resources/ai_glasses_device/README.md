To recreate or update the image XMP metadata from scratch, you can use the following commands:
```bash
# 1. Prepare the XMP metadata for study-360.jpg:
echo '<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:GPano="http://ns.google.com/photos/1.0/panorama/"
    xmlns:androidemulator="urn:androidemulator:metadata:private:1.0">
   <dc:title>
    <rdf:Alt>
     <rdf:li xml:lang="x-default">Study</rdf:li>
    </rdf:Alt>
   </dc:title>
   <GPano:ProjectionType>equirectangular</GPano:ProjectionType>
   <GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>
   <androidemulator:isDefault>true</androidemulator:isDefault>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>' | exiftool "-xmp<=-" study-360.jpg

# 2. Prepare the XMP metadata for city-360.jpg:
echo '<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:GPano="http://ns.google.com/photos/1.0/panorama/">
   <dc:title>
    <rdf:Alt>
     <rdf:li xml:lang="x-default">City</rdf:li>
    </rdf:Alt>
   </dc:title>
   <GPano:ProjectionType>equirectangular</GPano:ProjectionType>
   <GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>' | exiftool "-xmp<=-" city-360.jpg

# 3. Prepare the XMP metadata for kitchen-360.jpg:
echo '<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:GPano="http://ns.google.com/photos/1.0/panorama/">
   <dc:title>
    <rdf:Alt>
     <rdf:li xml:lang="x-default">Kitchen</rdf:li>
    </rdf:Alt>
   </dc:title>
   <GPano:ProjectionType>equirectangular</GPano:ProjectionType>
   <GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>' | exiftool "-xmp<=-" kitchen-360.jpg

# 4. Prepare the XMP metadata for forest-360.jpg:
echo '<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:GPano="http://ns.google.com/photos/1.0/panorama/">
   <dc:title>
    <rdf:Alt>
     <rdf:li xml:lang="x-default">Forest</rdf:li>
    </rdf:Alt>
   </dc:title>
   <GPano:ProjectionType>equirectangular</GPano:ProjectionType>
   <GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>' | exiftool "-xmp<=-" forest-360.jpg
```

Note: standard `exiftool` will add `<?xpacket?>` headers when writing, which is standard and parses cleanly.
