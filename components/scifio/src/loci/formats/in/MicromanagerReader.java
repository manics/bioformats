/*
 * #%L
 * OME SCIFIO package for reading and converting scientific file formats.
 * %%
 * Copyright (C) 2005 - 2012 Open Microscopy Environment:
 *   - Board of Regents of the University of Wisconsin-Madison
 *   - Glencoe Software, Inc.
 *   - University of Dundee
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 * The views and conclusions contained in the software and documentation are
 * those of the authors and should not be interpreted as representing official
 * policies, either expressed or implied, of any organization.
 * #L%
 */

package loci.formats.in;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.StringTokenizer;
import java.util.Vector;

import loci.common.DataTools;
import loci.common.DateTools;
import loci.common.Location;
import loci.common.RandomAccessInputStream;
import loci.common.xml.BaseHandler;
import loci.common.xml.XMLTools;
import loci.formats.CoreMetadata;
import loci.formats.FormatException;
import loci.formats.FormatReader;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
import loci.formats.meta.MetadataStore;
import ome.xml.model.primitives.PositiveFloat;
import ome.xml.model.primitives.Timestamp;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * MicromanagerReader is the file format reader for Micro-Manager files.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="http://trac.openmicroscopy.org.uk/ome/browser/bioformats.git/components/bio-formats/src/loci/formats/in/MicromanagerReader.java">Trac</a>,
 * <a href="http://git.openmicroscopy.org/?p=bioformats.git;a=blob;f=components/bio-formats/src/loci/formats/in/MicromanagerReader.java;hb=HEAD">Gitweb</a></dd></dl>
 */
public class MicromanagerReader extends FormatReader {

  // -- Constants --

  public static final String DATE_FORMAT = "EEE MMM dd HH:mm:ss zzz yyyy";

  /** File containing extra metadata. */
  private static final String METADATA = "metadata.txt";

  /**
   * Optional file containing additional acquisition parameters.
   * (And yes, the spelling is correct.)
   */
  private static final String XML = "Acqusition.xml";

  // -- Fields --

  /** Helper reader for TIFF files. */
  private MinimalTiffReader tiffReader;

  private Vector<Position> positions;

  // -- Constructor --

  /** Constructs a new Micromanager reader. */
  public MicromanagerReader() {
    super("Micro-Manager", new String[] {"tif", "tiff", "txt", "xml"});
    domains = new String[] {FormatTools.LM_DOMAIN};
    hasCompanionFiles = true;
    datasetDescription = "A 'metadata.txt' file plus or or more .tif files";
  }

  // -- IFormatReader API methods --

  /* @see loci.formats.IFormatReader#isSingleFile(String) */
  public boolean isSingleFile(String id) throws FormatException, IOException {
    return false;
  }

  /* @see loci.formats.IFormatReader#isThisType(String, boolean) */
  public boolean isThisType(String name, boolean open) {
    if (!open) return false; // not allowed to touch the file system
    if (name.equals(METADATA) || name.endsWith(File.separator + METADATA) ||
      name.equals(XML) || name.endsWith(File.separator + XML))
    {
      final int blockSize = 1048576;
      try {
        RandomAccessInputStream stream = new RandomAccessInputStream(name);
        long length = stream.length();
        String data = stream.readString((int) Math.min(blockSize, length));
        stream.close();
        return length > 0 && (data.indexOf("Micro-Manager") >= 0 ||
          data.indexOf("micromanager") >= 0);
      }
      catch (IOException e) {
        return false;
      }
    }
    try {
      Location parent = new Location(name).getAbsoluteFile().getParentFile();
      Location metaFile = new Location(parent, METADATA);
      RandomAccessInputStream s = new RandomAccessInputStream(name);
      boolean validTIFF = isThisType(s);
      s.close();
      return validTIFF && isThisType(metaFile.getAbsolutePath(), open);
    }
    catch (NullPointerException e) { }
    catch (IOException e) { }
    return false;
  }

  /* @see loci.formats.IFormatReader#fileGroupOption(String) */
  public int fileGroupOption(String id) throws FormatException, IOException {
    return FormatTools.MUST_GROUP;
  }

  /* @see loci.formats.IFormatReader#isThisType(RandomAccessInputStream) */
  public boolean isThisType(RandomAccessInputStream stream) throws IOException
  {
    if (tiffReader == null) tiffReader = new MinimalTiffReader();
    return tiffReader.isThisType(stream);
  }

  /* @see loci.formats.IFormatReader#getSeriesUsedFiles(boolean) */
  public String[] getSeriesUsedFiles(boolean noPixels) {
    FormatTools.assertId(currentId, true, 1);
    Vector<String> files = new Vector<String>();
    for (Position pos : positions) {
      files.add(pos.metadataFile);
      if (pos.xmlFile != null) {
        files.add(pos.xmlFile);
      }
      if (!noPixels) {
        for (String tiff : pos.tiffs) {
          if (new Location(tiff).exists()) {
            files.add(tiff);
          }
        }
      }
    }
    return files.toArray(new String[files.size()]);
  }

  /**
   * @see loci.formats.IFormatReader#openBytes(int, byte[], int, int, int, int)
   */
  public byte[] openBytes(int no, byte[] buf, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    FormatTools.checkPlaneParameters(this, no, buf.length, x, y, w, h);

    String file = positions.get(getSeries()).getFile(no);

    if (file != null && new Location(file).exists()) {
      tiffReader.setId(file);
      return tiffReader.openBytes(0, buf, x, y, w, h);
    }
    LOGGER.warn("File for image #{} ({}) is missing.", no, file);
    return buf;
  }

  /* @see loci.formats.IFormatReader#close(boolean) */
  public void close(boolean fileOnly) throws IOException {
    super.close(fileOnly);
    if (tiffReader != null) tiffReader.close(fileOnly);
    if (!fileOnly) {
      positions = null;
    }
  }

  /* @see loci.formats.IFormatReader#getOptimalTileWidth() */
  public int getOptimalTileWidth() {
    FormatTools.assertId(currentId, true, 1);
    if (tiffReader.getCurrentFile() == null) {
      setupReader();
    }
    return tiffReader.getOptimalTileWidth();
  }

  /* @see loci.formats.IFormatReader#getOptimalTileHeight() */
  public int getOptimalTileHeight() {
    FormatTools.assertId(currentId, true, 1);
    if (tiffReader.getCurrentFile() == null) {
      setupReader();
    }
    return tiffReader.getOptimalTileHeight();
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  public void initFile(String id) throws FormatException, IOException {
    super.initFile(id);
    tiffReader = new MinimalTiffReader();
    positions = new Vector<Position>();

    LOGGER.info("Reading metadata file");

    // find metadata.txt

    Location file = new Location(currentId).getAbsoluteFile();
    Location parentFile = file.getParentFile();
    String metadataFile = METADATA;
    if (file.exists()) {
      metadataFile = new Location(parentFile, METADATA).getAbsolutePath();

      // look for other positions

      if (parentFile.getName().indexOf("Pos_") >= 0) {
        parentFile = parentFile.getParentFile();
        String[] dirs = parentFile.list(true);
        Arrays.sort(dirs);
        for (String dir : dirs) {
          if (dir.indexOf("Pos_") >= 0) {
            Position pos = new Position();
            Location posDir = new Location(parentFile, dir);
            pos.metadataFile = new Location(posDir, METADATA).getAbsolutePath();
            positions.add(pos);
          }
        }
      }
      else {
        Position pos = new Position();
        pos.metadataFile = metadataFile;
        positions.add(pos);
      }
    }

    core = new CoreMetadata[positions.size()];

    for (int i=0; i<positions.size(); i++) {
      core[i] = new CoreMetadata();
      setSeries(i);
      parsePosition(i);
    }
    setSeries(0);

    populateMetadata();
  }

  private void populateMetadata() throws FormatException, IOException {
    MetadataStore store = makeFilterMetadata();
    MetadataTools.populatePixels(store, this, true);

    String instrumentID = MetadataTools.createLSID("Instrument", 0);
    store.setInstrumentID(instrumentID, 0);

    for (int i=0; i<positions.size(); i++) {
      Position p = positions.get(i);
      if (p.time != null) {
        String date = DateTools.formatDate(p.time, DATE_FORMAT);
        if (date != null) {
          store.setImageAcquisitionDate(new Timestamp(date), i);
        }
      }

      if (positions.size() > 1) {
        Location parent = new Location(p.metadataFile).getParentFile();
        store.setImageName(parent.getName(), i);
      }

      if (getMetadataOptions().getMetadataLevel() != MetadataLevel.MINIMUM) {
        store.setImageDescription(p.comment, i);

        // link Instrument and Image
        store.setImageInstrumentRef(instrumentID, i);

        for (int c=0; c<p.channels.length; c++) {
          store.setChannelName(p.channels[c], i, c);
        }

        if (p.pixelSize != null && p.pixelSize > 0) {
          store.setPixelsPhysicalSizeX(new PositiveFloat(p.pixelSize), i);
          store.setPixelsPhysicalSizeY(new PositiveFloat(p.pixelSize), i);
        }
        else {
          LOGGER.warn("Expected positive value for PhysicalSizeX; got {}",
            p.pixelSize);
        }
        if (p.sliceThickness != null && p.sliceThickness > 0) {
          store.setPixelsPhysicalSizeZ(new PositiveFloat(p.sliceThickness), i);
        }
        else {
          LOGGER.warn("Expected positive value for PhysicalSizeZ; got {}",
            p.sliceThickness);
        }

        int nextStamp = 0;
        for (int q=0; q<getImageCount(); q++) {
          store.setPlaneExposureTime(p.exposureTime, i, q);
          String tiff = positions.get(getSeries()).getFile(q);
          if (tiff != null && new Location(tiff).exists() &&
            nextStamp < p.timestamps.length)
          {
            store.setPlaneDeltaT(p.timestamps[nextStamp++], i, q);
          }
        }

        String serialNumber = p.detectorID;
        p.detectorID = MetadataTools.createLSID("Detector", 0, i);

        for (int c=0; c<p.channels.length; c++) {
          store.setDetectorSettingsBinning(getBinning(p.binning), i, c);
          store.setDetectorSettingsGain(new Double(p.gain), i, c);
          if (c < p.voltage.size()) {
            store.setDetectorSettingsVoltage(p.voltage.get(c), i, c);
          }
          store.setDetectorSettingsID(p.detectorID, i, c);
        }

        store.setDetectorID(p.detectorID, 0, i);
        if (p.detectorModel != null) {
          store.setDetectorModel(p.detectorModel, 0, i);
        }

        if (serialNumber != null) {
          store.setDetectorSerialNumber(serialNumber, 0, i);
        }

        if (p.detectorManufacturer != null) {
          store.setDetectorManufacturer(p.detectorManufacturer, 0, i);
        }

        if (p.cameraMode == null) p.cameraMode = "Other";
        store.setDetectorType(getDetectorType(p.cameraMode), 0, i);
        store.setImagingEnvironmentTemperature(p.temperature, i);
      }
    }
  }

  public void populateMetadataStore(String[] jsonData)
    throws FormatException, IOException
  {
    FormatTools.assertId(currentId, false, 1);
    currentId = "in-memory-json";
    core = new CoreMetadata[jsonData.length];
    positions = new Vector<Position>();
    for (int pos=0; pos<jsonData.length; pos++) {
      core[pos] = new CoreMetadata();
      Position p = new Position();
      p.metadataFile = "Position #" + (pos + 1);
      positions.add(p);
      setSeries(pos);
      parsePosition(jsonData[pos], pos);
    }
    setSeries(0);
    populateMetadata();
  }

  // -- Helper methods --

  private void parsePosition(int posIndex) throws IOException, FormatException {
    Position p = positions.get(posIndex);
    String s = DataTools.readFile(p.metadataFile);
    parsePosition(s, posIndex);

    buildTIFFList(posIndex);
  }

  private void buildTIFFList(int posIndex) throws FormatException {
    Position p = positions.get(posIndex);
    String parent = new Location(p.metadataFile).getParent();

    LOGGER.info("Finding image file names");

    // find the name of a TIFF file
    p.tiffs = new Vector<String>();

    // build list of TIFF files

    buildTIFFList(posIndex, parent + File.separator + p.baseTiff);

    if (p.tiffs.size() == 0) {
      Vector<String> uniqueZ = new Vector<String>();
      Vector<String> uniqueC = new Vector<String>();
      Vector<String> uniqueT = new Vector<String>();

      Location dir =
        new Location(p.metadataFile).getAbsoluteFile().getParentFile();
      String[] files = dir.list(true);
      Arrays.sort(files);
      for (String f : files) {
        if (checkSuffix(f, "tif") || checkSuffix(f, "tiff")) {
          String[] blocks = f.split("_");
          if (!uniqueT.contains(blocks[1])) uniqueT.add(blocks[1]);
          if (!uniqueC.contains(blocks[2])) uniqueC.add(blocks[2]);
          if (!uniqueZ.contains(blocks[3])) uniqueZ.add(blocks[3]);

          p.tiffs.add(new Location(dir, f).getAbsolutePath());
        }
      }

      core[posIndex].sizeZ = uniqueZ.size();
      core[posIndex].sizeC = uniqueC.size();
      core[posIndex].sizeT = uniqueT.size();

      if (p.tiffs.size() == 0) {
        throw new FormatException("Could not find TIFF files.");
      }
    }
  }

  private void parsePosition(String jsonData, int posIndex)
    throws IOException, FormatException
  {
    Position p = positions.get(posIndex);
    String parent = new Location(p.metadataFile).getParent();

    // now parse the rest of the metadata

    // metadata.txt looks something like this:
    //
    // {
    //   "Section Name": {
    //      "Key": "Value",
    //      "Array key": [
    //        first array value, second array value
    //      ]
    //   }
    //
    // }

    LOGGER.info("Populating metadata");

    Vector<Double> stamps = new Vector<Double>();
    p.voltage = new Vector<Double>();

    StringTokenizer st = new StringTokenizer(jsonData, "\n");
    int[] slice = new int[3];
    while (st.hasMoreTokens()) {
      String token = st.nextToken().trim();
      boolean open = token.indexOf("[") != -1;
      boolean closed = token.indexOf("]") != -1;
      if (open || (!open && !closed && !token.equals("{") &&
        !token.startsWith("}")))
      {
        int quote = token.indexOf("\"") + 1;
        String key = token.substring(quote, token.indexOf("\"", quote));
        String value = null;

        if (open == closed) {
          value = token.substring(token.indexOf(":") + 1);
        }
        else if (!closed) {
          StringBuffer valueBuffer = new StringBuffer();
          while (!closed) {
            token = st.nextToken();
            closed = token.indexOf("]") != -1;
            valueBuffer.append(token);
          }
          value = valueBuffer.toString();
          value = value.replaceAll("\n", "");
        }
        if (value == null) continue;

        int startIndex = value.indexOf("[");
        int endIndex = value.indexOf("]");
        if (endIndex == -1) endIndex = value.length();

        value = value.substring(startIndex + 1, endIndex).trim();
        if (value.length() == 0) {
          continue;
        }
        value = value.substring(0, value.length() - 1);
        value = value.replaceAll("\"", "");
        if (value.endsWith(",")) value = value.substring(0, value.length() - 1);
        addSeriesMeta(key, value);
        if (key.equals("Channels")) {
          core[posIndex].sizeC = Integer.parseInt(value);
        }
        else if (key.equals("ChNames")) {
          p.channels = value.split(",");
          for (int q=0; q<p.channels.length; q++) {
            p.channels[q] = p.channels[q].replaceAll("\"", "").trim();
          }
        }
        else if (key.equals("Frames")) {
          core[posIndex].sizeT = Integer.parseInt(value);
        }
        else if (key.equals("Slices")) {
          core[posIndex].sizeZ = Integer.parseInt(value);
        }
        else if (key.equals("PixelSize_um")) {
          p.pixelSize = new Double(value);
        }
        else if (key.equals("z-step_um")) {
          p.sliceThickness = new Double(value);
        }
        else if (key.equals("Time")) {
          p.time = value;
        }
        else if (key.equals("Comment")) {
          p.comment = value;
        }
        else if (key.equals("FileName")) {
          p.fileNameMap.put(new Index(slice), value);
          if (p.baseTiff == null) {
            p.baseTiff = value;
          }
        }
        else if (key.equals("Width")) {
          core[posIndex].sizeX = Integer.parseInt(value);
        }
        else if (key.equals("Height")) {
          core[posIndex].sizeY = Integer.parseInt(value);
        }
        else if (key.equals("IJType")) {
          int type = Integer.parseInt(value);

          switch (type) {
            case 0:
              core[posIndex].pixelType = FormatTools.UINT8;
              break;
            case 1:
              core[posIndex].pixelType = FormatTools.UINT16;
              break;
            default:
              throw new FormatException("Unknown type: " + type);
          }
        }
      }

      if (token.startsWith("\"FrameKey")) {
        int dash = token.indexOf("-") + 1;
        int nextDash = token.indexOf("-", dash);
        slice[2] = Integer.parseInt(token.substring(dash, nextDash));
        dash = nextDash + 1;
        nextDash = token.indexOf("-", dash);
        slice[1] = Integer.parseInt(token.substring(dash, nextDash));
        dash = nextDash + 1;
        slice[0] = Integer.parseInt(token.substring(dash,
          token.indexOf("\"", dash)));

        token = st.nextToken().trim();
        String key = "", value = "";
        boolean valueArray = false;

        while (!token.startsWith("}")) {
          if (valueArray) {
            if (token.trim().equals("],")) {
              valueArray = false;
            }
            else {
              value += token.trim().replaceAll("\"", "");
              token = st.nextToken().trim();
              continue;
            }
          }
          else {
            int colon = token.indexOf(":");
            key = token.substring(1, colon).trim();
            value = token.substring(colon + 1, token.length() - 1).trim();

            key = key.replaceAll("\"", "");
            value = value.replaceAll("\"", "");

            if (token.trim().endsWith("[")) {
              valueArray = true;
              token = st.nextToken().trim();
              continue;
            }
          }

          addSeriesMeta(key, value);

          if (key.equals("Exposure-ms")) {
            double t = Double.parseDouble(value);
            p.exposureTime = new Double(t / 1000);
          }
          else if (key.equals("ElapsedTime-ms")) {
            double t = Double.parseDouble(value);
            stamps.add(new Double(t / 1000));
          }
          else if (key.equals("Core-Camera")) p.cameraRef = value;
          else if (key.equals(p.cameraRef + "-Binning")) {
            if (value.indexOf("x") != -1) p.binning = value;
            else p.binning = value + "x" + value;
          }
          else if (key.equals(p.cameraRef + "-CameraID")) p.detectorID = value;
          else if (key.equals(p.cameraRef + "-CameraName")) {
            p.detectorModel = value;
          }
          else if (key.equals(p.cameraRef + "-Gain")) {
            p.gain = (int) Double.parseDouble(value);
          }
          else if (key.equals(p.cameraRef + "-Name")) {
            p.detectorManufacturer = value;
          }
          else if (key.equals(p.cameraRef + "-Temperature")) {
            p.temperature = Double.parseDouble(value);
          }
          else if (key.equals(p.cameraRef + "-CCDMode")) {
            p.cameraMode = value;
          }
          else if (key.startsWith("DAC-") && key.endsWith("-Volts")) {
            p.voltage.add(new Double(value));
          }
          else if (key.equals("FileName")) {
            p.fileNameMap.put(new Index(slice), value);
            if (p.baseTiff == null) {
              p.baseTiff = value;
            }
          }

          token = st.nextToken().trim();
        }
      }
    }

    p.timestamps = stamps.toArray(new Double[stamps.size()]);
    Arrays.sort(p.timestamps);

    // look for the optional companion XML file

    if (new Location(parent, XML).exists()) {
      p.xmlFile = new Location(parent, XML).getAbsolutePath();
      parseXMLFile();
    }

    if (getSizeZ() == 0) core[posIndex].sizeZ = 1;
    if (getSizeT() == 0) core[posIndex].sizeT = 1;

    core[posIndex].dimensionOrder = "XYZCT";
    core[posIndex].interleaved = false;
    core[posIndex].rgb = false;
    core[posIndex].littleEndian = false;
    core[posIndex].imageCount = getSizeZ() * getSizeC() * getSizeT();
    core[posIndex].indexed = false;
    core[posIndex].falseColor = false;
    core[posIndex].metadataComplete = true;
  }

  /**
   * Populate the list of TIFF files using the given file name as a pattern.
   */
  private void buildTIFFList(int posIndex, String baseTiff) {
    LOGGER.info("Building list of TIFFs");
    Position p = positions.get(posIndex);
    String prefix = "";
    if (baseTiff.indexOf(File.separator) != -1) {
      prefix = baseTiff.substring(0, baseTiff.lastIndexOf(File.separator) + 1);
      baseTiff = baseTiff.substring(baseTiff.lastIndexOf(File.separator) + 1);
    }

    String[] blocks = baseTiff.split("_");
    StringBuffer filename = new StringBuffer();
    for (int t=0; t<getSizeT(); t++) {
      for (int c=0; c<getSizeC(); c++) {
        for (int z=0; z<getSizeZ(); z++) {
          // file names are of format:
          // img_<T>_<channel name>_<T>.tif
          filename.append(prefix);
          if (!prefix.endsWith(File.separator) &&
            !blocks[0].startsWith(File.separator))
          {
            filename.append(File.separator);
          }
          filename.append(blocks[0]);
          filename.append("_");

          int zeros = blocks[1].length() - String.valueOf(t).length();
          for (int q=0; q<zeros; q++) {
            filename.append("0");
          }
          filename.append(t);
          filename.append("_");

          String channel = p.channels[c];
          if (channel.indexOf("-") != -1) {
            channel = channel.substring(0, channel.indexOf("-"));
          }
          filename.append(channel);
          filename.append("_");

          zeros = blocks[3].length() - String.valueOf(z).length() - 4;
          for (int q=0; q<zeros; q++) {
            filename.append("0");
          }
          filename.append(z);
          filename.append(".tif");

          p.tiffs.add(filename.toString());
          filename.delete(0, filename.length());
        }
      }
    }
  }

  /** Parse metadata values from the Acqusition.xml file. */
  private void parseXMLFile() throws IOException {
    Position p = positions.get(getSeries());
    String xmlData = DataTools.readFile(p.xmlFile);
    xmlData = XMLTools.sanitizeXML(xmlData);

    DefaultHandler handler = new MicromanagerHandler();
    XMLTools.parseXML(xmlData, handler);
  }

  /** Initialize the TIFF reader with the first file in the current series. */
  private void setupReader() {
    try {
      String file = positions.get(getSeries()).getFile(0);
      tiffReader.setId(file);
    }
    catch (Exception e) {
      LOGGER.debug("", e);
    }
  }

  // -- Helper classes --

  /** SAX handler for parsing Acqusition.xml. */
  class MicromanagerHandler extends BaseHandler {
    public void startElement(String uri, String localName, String qName,
      Attributes attributes)
    {
      if (qName.equals("entry")) {
        String key = attributes.getValue("key");
        String value = attributes.getValue("value");

        addSeriesMeta(key, value);
      }
    }
  }

  class Position {
    public String baseTiff;
    public Vector<String> tiffs;
    public HashMap<Index, String> fileNameMap = new HashMap<Index, String>();

    public String metadataFile;
    public String xmlFile;

    public String[] channels;

    public String comment, time;
    public Double exposureTime, sliceThickness, pixelSize;
    public Double[] timestamps;

    public int gain;
    public String binning, detectorID, detectorModel, detectorManufacturer;
    public double temperature;
    public Vector<Double> voltage;
    public String cameraRef;
    public String cameraMode;

    public String getFile(int no) {
      int[] zct = getZCTCoords(no);
      for (Index key : fileNameMap.keySet()) {
        if (key.z == zct[0] && key.c == zct[1] && key.t == zct[2]) {
          String file = fileNameMap.get(key);

          if (tiffs != null) {
            for (String tiff : tiffs) {
              if (tiff.endsWith(File.separator + file)) {
                return tiff;
              }
            }
          }
        }
      }
      return fileNameMap.size() == 0 ? tiffs.get(no) : null;
    }
  }

  class Index {
    public int z;
    public int c;
    public int t;

    public Index(int[] zct) {
      z = zct[0];
      c = zct[1];
      t = zct[2];
    }
  }

}
