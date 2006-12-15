package net.sf.pzfilereader.brparse;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.sf.pzfilereader.DataSet;
import net.sf.pzfilereader.DefaultDataSet;
import net.sf.pzfilereader.DelimiterPZParser;
import net.sf.pzfilereader.structure.Row;
import net.sf.pzfilereader.util.PZConstants;
import net.sf.pzfilereader.util.ParserUtils;

public class BuffReaderDelimPZParser extends DelimiterPZParser {    
    private BufferedReader br;
    private InputStreamReader isr;
    private boolean processedFirst = false;

    public BuffReaderDelimPZParser(final File pzmapXML, final File dataSource, final char delimiter, final char qualifier,
            final boolean ignoreFirstRecord) {
        super(dataSource, delimiter, qualifier, ignoreFirstRecord);
    }

    public BuffReaderDelimPZParser(final InputStream pzmapXMLStream, final InputStream dataSourceStream, final char delimiter,
            final char qualifier, final boolean ignoreFirstRecord) {
        super(dataSourceStream, delimiter, qualifier, ignoreFirstRecord);
    }

    public BuffReaderDelimPZParser(final File dataSource, final char delimiter, final char qualifier, final boolean ignoreFirstRecord) {
        super(dataSource, delimiter, qualifier, ignoreFirstRecord);
    }

    public BuffReaderDelimPZParser(final InputStream dataSourceStream, final char delimiter, final char qualifier,
            final boolean ignoreFirstRecord) {
        super(dataSourceStream, delimiter, qualifier, ignoreFirstRecord);
    }
    
    
    public DataSet doParse() {
        final DataSet ds = new BuffReaderDelimPZDataSet(getColumnMD(), this);
        try {
            //gather the conversion properties
            ds.setPZConvertProps(ParserUtils.loadConvertProperties());
            
            if (getDataSourceStream() == null) {
                setDataSourceStream(ParserUtils.createInputStream(getDataSource()));
            }
            
            isr = new InputStreamReader(getDataSourceStream());
            br = new BufferedReader(isr);
            
            return ds;
        
        } catch(IOException ex) {
            ex.printStackTrace();
        }
        
        return null;
    }
    
    /**
     * Reads in the next record on the file and return a row
     * 
     * @param ds
     * @return Row
     * @throws IOException
     */
    public Row buildRow(final DefaultDataSet ds) throws IOException{
        /** loop through each line in the file */
        while (true) {
            String line = fetchNextRecord(br, getQualifier(), getDelimiter());
            
            if (line == null) {
                return null;
            }
            
            // check to see if the user has elected to skip the first record
            if (!processedFirst && isIgnoreFirstRecord()) {
                processedFirst = true;
                continue;
            } else if (!processedFirst && shouldCreateMDFromFile()) {
                processedFirst = true;
                setColumnMD(ParserUtils.getColumnMDFromFile(line, getDelimiter(), getQualifier()));
                continue;
            }
    
             //TODO
            //seems like we may want to try doing something like this.  I have my reservations because
            //it is possible that we don't get a "detail" id and this might generate NPE
            //is it going to create too much overhead to do a null check here as well???
            //final int intialSize =  ParserUtils.getColumnMetaData(PZConstants.DETAIL_ID, getColumnMD()).size();
            // column values
            final List columns = ParserUtils.splitLine(line, getDelimiter(), getQualifier(), PZConstants.SPLITLINE_SIZE_INIT);
            final String mdkey = ParserUtils.getCMDKeyForDelimitedFile(getColumnMD(), columns);
            final List cmds = ParserUtils.getColumnMetaData(mdkey, getColumnMD());
            final int columnCount = cmds.size();
            // DEBUG

            // Incorrect record length on line log the error. Line
            // will not be included in the dataset
            if (columns.size() > columnCount) {
                // log the error
                addError(ds, "TOO MANY COLUMNS WANTED: " + columnCount + " GOT: " + columns.size(), getLineCount(), 2);
                continue;
            } else if (columns.size() < columnCount) {
                if (isHandlingShortLines()) {
                    // We can pad this line out
                    while (columns.size() < columnCount) {
                        columns.add("");
                    }

                    // log a warning
                    addError(ds, "PADDED LINE TO CORRECT NUMBER OF COLUMNS", getLineCount(), 1);

                } else {
                    addError(ds, "TOO FEW COLUMNS WANTED: " + columnCount + " GOT: " + columns.size(), getLineCount(), 2);
                    continue;
                }
            }

            final Row row = new Row();
            row.setMdkey(mdkey.equals(PZConstants.DETAIL_ID) ? null : mdkey); // try
            // to limit the memory use
            row.setCols(columns);
            row.setRowNumber(getLineCount());
            
            return row;             
        }
    }
    
    /**
     * Closes out the file readers
     *
     *@throws IOException
     */
    public void close() throws IOException{
        if (br != null) {
            br.close();
        }
        if (isr != null) {
            isr.close();
        }
    }
    
    /**
     * Returns the meta data describing the columns
     */
    public Map getColumnMD() {
        return super.getColumnMD();
    }
}
