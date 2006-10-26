/*
 Copyright 2006 Paul Zepernick

 Licensed under the Apache License, Version 2.0 (the "License"); 
 you may not use this file except in compliance with the License. 
 You may obtain a copy of the License at 

 http://www.apache.org/licenses/LICENSE-2.0 

 Unless required by applicable law or agreed to in writing, software distributed 
 under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR 
 CONDITIONS OF ANY KIND, either express or implied. See the License for 
 the specific language governing permissions and limitations under the License.  
 */
package net.sf.pzfilereader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

import net.sf.pzfilereader.ordering.OrderBy;
import net.sf.pzfilereader.structure.ColumnMetaData;
import net.sf.pzfilereader.structure.Row;
import net.sf.pzfilereader.util.ExcelTransformer;
import net.sf.pzfilereader.util.PZConstants;
import net.sf.pzfilereader.util.ParserUtils;
import net.sf.pzfilereader.xml.PZMapParser;

/**
 * This class parses a datafile and holds methods to scroll back and forth
 * through the datafile along with methods to retreive values from columns.
 * 
 * @author Paul Zepernick
 * @version 2.0.1
 * @todo Ought to implement an interface for the access to data.
 */
public class DataSet implements IDataSet {
    /** Array to hold the rows and their values in the text file */
    private List rows = null;

    /** Array of errors that have occured during processing */
    private List errors = null;

    /** Map of column metadata's */
    private Map columnMD = null;

    /** Pointer for the current row in the array we are on */
    private int pointer = -1;

    /** flag to indicate if data should be pulled as lower case */
    private boolean lowerCase = false;

    /** flag to inidicate if data should be pulled as upper case */
    private boolean upperCase = false;

    /**
     * flag to indicate if a strict parse should be used when getting doubles
     * and ints
     */
    private boolean strictNumericParse = false;

    /**
     * Flag to indicate that we can cope with lines shorter than the required
     * lengh
     */
    private boolean handleShortLines = false;

    /**
     * empty constructor. THIS SHOULD ONLY BE USED FOR CUSTOM DataSet
     * implementations. It provides NO parsing abilities
     */
    public DataSet() {
    }

    /**
     * Constructs a new DataSet using the database table file layout method.
     * This is used for a FIXED LENGTH text file.
     * 
     * @param con -
     *            Connection to database with DATAFILE and DATASTRUCTURE tables
     * @param dataSource -
     *            Fixed length file to read from
     * @param dataDefinition -
     *            Name of dataDefinition in the DATAFILE table DATAFILE_DESC
     *            column
     * @param handleShortLines -
     *            Pad lines out to fit the fixed length
     * @exception Exception
     */
    public DataSet(final Connection con, final File dataSource, final String dataDefinition, final boolean handleShortLines)
            throws Exception {
        this(con, ParserUtils.createInputStream(dataSource), dataDefinition, handleShortLines);
    }

    /**
     * Constructs a new DataSet using the database table file layout method.
     * This is used for a FIXED LENGTH text file.
     * 
     * @param con -
     *            Connection to database with DATAFILE and DATASTRUCTURE tables
     * @param dataSourceStream -
     *            text file datasource InputStream to read from
     * @param dataDefinition -
     *            Name of dataDefinition in the DATAFILE table DATAFILE_DESC
     *            column
     * @param handleShortLines -
     *            Pad lines out to fit the fixed length
     * @exception Exception
     */
    public DataSet(final Connection con, final InputStream dataSourceStream, final String dataDefinition,
            final boolean handleShortLines) throws Exception {
        super();
        this.handleShortLines = handleShortLines;

        ResultSet rs = null;
        PreparedStatement stmt = null;

        try {
            columnMD = new LinkedHashMap();

            final String sql = "SELECT * FROM DATAFILE INNER JOIN DATASTRUCTURE ON "
                    + "DATAFILE.DATAFILE_NO = DATASTRUCTURE.DATAFILE_NO " + "WHERE DATAFILE.DATAFILE_DESC = '" + dataDefinition
                    + "' " + "ORDER BY DATASTRUCTURE_COL_ORDER";

            stmt = con.prepareStatement(sql); // always use PreparedStatement
            // as the DB can do clever
            // things.
            rs = stmt.executeQuery();

            int recPosition = 1;
            final List cmds = new ArrayList();
            // put array of columns together. These will be used to put together
            // the dataset when reading in the file
            while (rs.next()) {

                final ColumnMetaData column = new ColumnMetaData();
                column.setColName(rs.getString("DATASTRUCTURE_COLUMN"));
                column.setColLength(rs.getInt("DATASTRUCTURE_LENGTH"));
                column.setStartPosition(recPosition);
                column.setEndPosition(recPosition + (rs.getInt("DATASTRUCTURE_LENGTH") - 1));
                recPosition += rs.getInt("DATASTRUCTURE_LENGTH");

                cmds.add(column);
            }

            columnMD.put(PZConstants.DETAIL_ID, cmds);
            columnMD.put(PZConstants.COL_IDX, ParserUtils.buidColumnIndexMap(cmds));

            if (cmds.isEmpty()) {
                throw new FileNotFoundException("DATA DEFINITION CAN NOT BE FOUND IN THE DATABASE " + dataDefinition);
            }

            // read in the fixed length file and construct the DataSet object
            doFixedLengthFile(dataSourceStream);

        } finally {
            if (rs != null) {
                rs.close();
            }
            if (stmt != null) {
                stmt.close();
            }
        }

    }

    /**
     * Constructs a new DataSet using the database table file layout method.
     * This is used for a DELIMITED text file. esacpe sequence reference: \n
     * newline <br>
     * \t tab <br>
     * \b backspace <br>
     * \r return <br>
     * \f form feed <br> \\ backslash <br> \' single quote <br> \" double quote
     * 
     * @param con -
     *            Connection to database with DATAFILE and DATASTRUCTURE tables
     * @param dataSource -
     *            text file datasource to read from
     * @param dataDefinition -
     *            Name of dataDefinition in the DATAFILE table DATAFILE_DESC
     *            column
     * @param delimiter -
     *            Char the file is delimited By
     * @param qualifier -
     *            Char text is qualified by
     * @param ignoreFirstRecord -
     *            skips the first line that contains data in the file
     * @param handleShortLines -
     *            Adds missing columns as empty's to the DataSet instead of
     *            logging them as an error
     * @exception Exception
     * @deprecated use the char version
     */
    public DataSet(final Connection con, final File dataSource, final String dataDefinition, final String delimiter,
            final String qualifier, final boolean ignoreFirstRecord, final boolean handleShortLines) throws Exception {
        this(con, ParserUtils.createInputStream(dataSource), dataDefinition, delimiter, qualifier, ignoreFirstRecord,
                handleShortLines);
    }

    /**
     * New constructor based on InputStream. Constructs a new DataSet using the
     * database table file layout method. This is used for a DELIMITED text
     * file. esacpe sequence reference: \n newline <br>
     * \t tab <br>
     * \b backspace <br>
     * \r return <br>
     * \f form feed <br> \\ backslash <br> \' single quote <br> \" double quote
     * 
     * @param con -
     *            Connection to database with DATAFILE and DATASTRUCTURE tables
     * @param dataSourceStream -
     *            text file datasource InputStream to read from
     * @param dataDefinition -
     *            Name of dataDefinition in the DATAFILE table DATAFILE_DESC
     *            column
     * @param delimiter -
     *            Char the file is delimited By
     * @param qualifier -
     *            Char text is qualified by
     * @param ignoreFirstRecord -
     *            skips the first line that contains data in the file
     * @param handleShortLines -
     *            Adds missing columns as empty's to the DataSet instead of
     *            logging them as an error
     * @exception Exception
     * @deprecated qualifier and delimiters should only be char.
     */
    public DataSet(final Connection con, final InputStream dataSourceStream, final String dataDefinition, final String delimiter,
            final String qualifier, final boolean ignoreFirstRecord, final boolean handleShortLines) throws Exception {
        this(con, dataSourceStream, dataDefinition, delimiter != null ? delimiter.charAt(0) : 0, qualifier != null ? qualifier
                .charAt(0) : 0, ignoreFirstRecord, handleShortLines);
    }

    /**
     * New constructor based on InputStream. Constructs a new DataSet using the
     * database table file layout method. This is used for a DELIMITED text
     * file. esacpe sequence reference: \n newline <br>
     * \t tab <br>
     * \b backspace <br>
     * \r return <br>
     * \f form feed <br> \\ backslash <br> \' single quote <br> \" double quote
     * 
     * @param con -
     *            Connection to database with DATAFILE and DATASTRUCTURE tables
     * @param dataSourceStream -
     *            text file datasource InputStream to read from
     * @param dataDefinition -
     *            Name of dataDefinition in the DATAFILE table DATAFILE_DESC
     *            column
     * @param delimiter -
     *            Char the file is delimited By
     * @param qualifier -
     *            Char text is qualified by
     * @param ignoreFirstRecord -
     *            skips the first line that contains data in the file
     * @param handleShortLines -
     *            Adds missing columns as empty's to the DataSet instead of
     *            logging them as an error
     * @exception Exception
     */
    public DataSet(final Connection con, final InputStream dataSourceStream, final String dataDefinition, final char delimiter,
            final char qualifier, final boolean ignoreFirstRecord, final boolean handleShortLines) throws Exception {
        super();

        this.handleShortLines = handleShortLines;

        ResultSet rs = null;
        PreparedStatement stmt = null;
        try {
            columnMD = new LinkedHashMap();

            final String sql = "SELECT * FROM DATAFILE INNER JOIN DATASTRUCTURE ON "
                    + "DATAFILE.DATAFILE_NO = DATASTRUCTURE.DATAFILE_NO " + "WHERE DATAFILE.DATAFILE_DESC = '" + dataDefinition
                    + "' " + "ORDER BY DATASTRUCTURE_COL_ORDER";

            stmt = con.prepareStatement(sql);
            rs = stmt.executeQuery(); // always use PreparedStatement as the
            // DB can do clever things.

            final List cmds = new ArrayList();
            boolean hasResults = false;
            // put array of columns together. These will be used to put together
            // the dataset when reading in the file
            while (rs.next()) {

                final ColumnMetaData column = new ColumnMetaData();
                column.setColName(rs.getString("DATASTRUCTURE_COLUMN"));
                column.setColLength(rs.getInt("DATASTRUCTURE_LENGTH"));
                cmds.add(column);

                hasResults = true;
            }

            columnMD.put(PZConstants.DETAIL_ID, cmds);
            columnMD.put(PZConstants.COL_IDX, ParserUtils.buidColumnIndexMap(cmds));

            if (!hasResults) {
                throw new FileNotFoundException("DATA DEFINITION CAN NOT BE FOUND IN THE DATABASE " + dataDefinition);
            }

            // read in the fixed length file and construct the DataSet object
            doDelimitedFile(dataSourceStream, delimiter, qualifier, ignoreFirstRecord, false);

        } finally {
            if (rs != null) {
                rs.close();
            }
            if (stmt != null) {
                stmt.close();
            }
        }

    }

    /**
     * Constructs a new DataSet using the PZMAP XML file layout method. This is
     * used for a DELIMITED text file. esacpe sequence reference: \n newline
     * <br>
     * \t tab <br>
     * \b backspace <br>
     * \r return <br>
     * \f form feed <br> \\ backslash <br> \' single quote <br> \" double quote
     * 
     * @param pzmapXML -
     *            Reference to the xml file holding the pzmap
     * @param dataSource -
     *            text file datasource to read from
     * @param delimiter -
     *            Char the file is delimited By
     * @param qualifier -
     *            Char text is qualified by
     * @param ignoreFirstRecord -
     *            skips the first line that contains data in the file
     * @param handleShortLines -
     *            Adds missing columns as empty's to the DataSet instead of
     *            logging them as an error
     * @exception Exception
     * @deprecated use the char version
     */
    public DataSet(final File pzmapXML, final File dataSource, final String delimiter, final String qualifier,
            final boolean ignoreFirstRecord, final boolean handleShortLines) throws Exception {
        this(ParserUtils.createInputStream(pzmapXML), ParserUtils.createInputStream(dataSource), delimiter, qualifier,
                ignoreFirstRecord, handleShortLines);
    }

    /**
     * New constructor based on InputStream. Constructs a new DataSet using the
     * PZMAP XML file layout method. This is used for a DELIMITED text file.
     * esacpe sequence reference: \n newline <br>
     * \t tab <br>
     * \b backspace <br>
     * \r return <br>
     * \f form feed <br> \\ backslash <br> \' single quote <br> \" double quote
     * 
     * @param pzmapXMLStream -
     *            Reference to the xml file holding the pzmap
     * @param dataSourceStream -
     *            text file datasource InputStream to read from
     * @param delimiter -
     *            Char the file is delimited By
     * @param qualifier -
     *            Char text is qualified by
     * @param ignoreFirstRecord -
     *            skips the first line that contains data in the file
     * @param handleShortLines -
     *            Adds missing columns as empty's to the DataSet instead of
     *            logging them as an error
     * @exception Exception
     * @deprecated use the char version
     */
    public DataSet(final InputStream pzmapXMLStream, final InputStream dataSourceStream, final String delimiter,
            final String qualifier, final boolean ignoreFirstRecord, final boolean handleShortLines) throws Exception {
        this(pzmapXMLStream, dataSourceStream, delimiter != null ? delimiter.charAt(0) : 0, qualifier != null ? qualifier
                .charAt(0) : 0, ignoreFirstRecord, handleShortLines);
    }

    
    /**
     * Constructs a new DataSet using the PZMAP XML file layout method. This is
     * used for a DELIMITED text file. esacpe sequence reference: \n newline
     * <br>
     * \t tab <br>
     * \b backspace <br>
     * \r return <br>
     * \f form feed <br> \\ backslash <br> \' single quote <br> \" double quote
     * 
     * @param pzmapXML -
     *            Reference to the xml file holding the pzmap
     * @param dataSource -
     *            text file datasource to read from
     * @param delimiter -
     *            Char the file is delimited By
     * @param qualifier -
     *            Char text is qualified by
     * @param ignoreFirstRecord -
     *            skips the first line that contains data in the file
     * @param handleShortLines -
     *            Adds missing columns as empty's to the DataSet instead of
     *            logging them as an error
     * @exception Exception
     */
    public DataSet(final File pzmapXML, final File dataSource, final char delimiter, final char qualifier,
            final boolean ignoreFirstRecord, final boolean handleShortLines) throws Exception {
        this(ParserUtils.createInputStream(pzmapXML), ParserUtils.createInputStream(dataSource), delimiter, qualifier,
                ignoreFirstRecord, handleShortLines);
    }
    
    /**
     * New constructor based on InputStream. Constructs a new DataSet using the
     * PZMAP XML file layout method. This is used for a DELIMITED text file.
     * esacpe sequence reference: \n newline <br>
     * \t tab <br>
     * \b backspace <br>
     * \r return <br>
     * \f form feed <br> \\ backslash <br> \' single quote <br> \" double quote
     * 
     * @param pzmapXMLStream -
     *            Reference to the xml file holding the pzmap
     * @param dataSourceStream -
     *            text file datasource InputStream to read from
     * @param delimiter -
     *            Char the file is delimited By
     * @param qualifier -
     *            Char text is qualified by
     * @param ignoreFirstRecord -
     *            skips the first line that contains data in the file
     * @param handleShortLines -
     *            Adds missing columns as empty's to the DataSet instead of
     *            logging them as an error
     * @exception Exception
     */
    public DataSet(final InputStream pzmapXMLStream, final InputStream dataSourceStream, final char delimiter,
            final char qualifier, final boolean ignoreFirstRecord, final boolean handleShortLines) throws Exception {

        this.handleShortLines = handleShortLines;
        columnMD = PZMapParser.parse(pzmapXMLStream);

        doDelimitedFile(dataSourceStream, delimiter, qualifier, ignoreFirstRecord, false);
    }

    /**
     * Constructs a new DataSet using the first line of data found in the text
     * file as the column names. This is used for a DELIMITED text file. esacpe
     * sequence reference: \n newline <br>
     * \t tab <br>
     * \b backspace <br>
     * \r return <br>
     * \f form feed <br> \\ backslash <br> \' single quote <br> \" double quote
     * 
     * @param dataSource -
     *            text file datasource to read from
     * @param delimiter -
     *            Char the file is delimited By
     * @param qualifier -
     *            Char text is qualified by
     * @param handleShortLines -
     *            when flaged as true, lines with less columns then the amount
     *            of column headers will be added as empty's instead of
     *            producing an error
     * @exception Exception
     * @deprecated
     */
    public DataSet(final File dataSource, final String delimiter, final String qualifier, final boolean handleShortLines)
            throws Exception {
        this(dataSource, delimiter != null ? delimiter.charAt(0) : 0, qualifier != null ? qualifier.charAt(0) : 0,
                handleShortLines);
    }

    /**
     * Constructs a new DataSet using the first line of data found in the text
     * file as the column names. This is used for a DELIMITED text file. esacpe
     * sequence reference: \n newline <br>
     * \t tab <br>
     * \b backspace <br>
     * \r return <br>
     * \f form feed <br> \\ backslash <br> \' single quote <br> \" double quote
     * 
     * @param dataSource -
     *            text file datasource to read from
     * @param delimiter -
     *            Char the file is delimited By
     * @param qualifier -
     *            Char text is qualified by
     * @param handleShortLines -
     *            when flaged as true, lines with less columns then the amount
     *            of column headers will be added as empty's instead of
     *            producing an error
     * @exception Exception
     */
    public DataSet(final File dataSource, final char delimiter, final char qualifier, final boolean handleShortLines)
            throws Exception {

        this.handleShortLines = handleShortLines;
        InputStream dataSourceStream = null;

        try {
            dataSourceStream = ParserUtils.createInputStream(dataSource);
            doDelimitedFile(dataSourceStream, delimiter, qualifier, false, true);
        } finally {
            if (dataSourceStream != null) {
                dataSourceStream.close();
            }
        }
    }

    /**
     * Constructs a new DataSet using the first line of data found in the text
     * file as the column names. This is used for a DELIMITED text file. esacpe
     * sequence reference: \n newline <br>
     * \t tab <br>
     * \b backspace <br>
     * \r return <br>
     * \f form feed <br> \\ backslash <br> \' single quote <br> \" double quote
     * 
     * @param dataSource -
     *            text file InputStream to read from
     * @param delimiter -
     *            Char the file is delimited By
     * @param qualifier -
     *            Char text is qualified by
     * @param handleShortLines -
     *            when flaged as true, lines with less columns then the amount
     *            of column headers will be added as empty's instead of
     *            producing an error
     * @exception Exception
     * @deprecated
     */
    public DataSet(final InputStream dataSource, final String delimiter, final String qualifier, final boolean handleShortLines)
            throws Exception {
        this(dataSource, delimiter != null ? delimiter.charAt(0) : 0, qualifier != null ? qualifier.charAt(0) : 0,
                handleShortLines);
    }

    /**
     * Constructs a new DataSet using the first line of data found in the text
     * file as the column names. This is used for a DELIMITED text file. esacpe
     * sequence reference: \n newline <br>
     * \t tab <br>
     * \b backspace <br>
     * \r return <br>
     * \f form feed <br> \\ backslash <br> \' single quote <br> \" double quote
     * 
     * @param dataSource -
     *            text file InputStream to read from
     * @param delimiter -
     *            Char the file is delimited By
     * @param qualifier -
     *            Char text is qualified by
     * @param handleShortLines -
     *            when flaged as true, lines with less columns then the amount
     *            of column headers will be added as empty's instead of
     *            producing an error
     * @exception Exception
     */
    public DataSet(final InputStream dataSource, final char delimiter, final char qualifier, final boolean handleShortLines)
            throws Exception {

        this.handleShortLines = handleShortLines;

        try {
            doDelimitedFile(dataSource, delimiter, qualifier, false, true);
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }

    /**
     * Constructs a new DataSet using the PZMAP XML file layout method. This is
     * used for a FIXED LENGTH text file.
     * 
     * @param pzmapXML -
     *            Reference to the xml file holding the pzmap
     * @param dataSource -
     *            Delimited file to read from
     * @param handleShortLines -
     *            Pad lines out to fit the fixed length
     * @exception Exception
     */
    public DataSet(final File pzmapXML, final File dataSource, final boolean handleShortLines) throws Exception {
        this(ParserUtils.createInputStream(pzmapXML), ParserUtils.createInputStream(dataSource), handleShortLines);
    }

    /**
     * New constructor based on InputStream. Constructs a new DataSet using the
     * PZMAP XML file layout method. This is used for a FIXED LENGTH text file.
     * 
     * @param pzmapXMLStream -
     *            Reference to the xml file InputStream holding the pzmap
     * @param dataSourceStream -
     *            Delimited file InputStream to read from
     * @param handleShortLines -
     *            Pad lines out to fit the fixed length
     * @exception Exception
     */
    public DataSet(final InputStream pzmapXMLStream, final InputStream dataSourceStream, final boolean handleShortLines)
            throws Exception {

        this.handleShortLines = handleShortLines;

        columnMD = PZMapParser.parse(pzmapXMLStream);

        // read in the fixed length file and construct the DataSet object
        doFixedLengthFile(dataSourceStream);
    }

    /*
     * This is the new version of doDelimitedFile using InputStrem instead of
     * File. This is more flexible especially it is working with WebStart.
     * 
     * puts together the dataset for fixed length file. This is used for PZ XML
     * mappings, and SQL table mappings
     */
    private void doFixedLengthFile(final InputStream dataSource) throws Exception {
        InputStreamReader isr = null;
        BufferedReader br = null;

        try {
            rows = new ArrayList();
            errors = new ArrayList();

            final Map recordLengths = ParserUtils.calculateRecordLengths(columnMD);

            // Read in the flat file
            isr = new InputStreamReader(dataSource);
            br = new BufferedReader(isr);
            String line = null;
            int lineCount = 0;
            // map of record lengths corrisponding to the ID's in the columnMD
            // array
            // loop through each line in the file
            while ((line = br.readLine()) != null) {
                lineCount++;
                // empty line skip past it
                if (line.trim().length() == 0) {
                    continue;
                }

                final String mdkey = ParserUtils.getCMDKeyForFixedLengthFile(columnMD, line);
                final int recordLength = ((Integer) recordLengths.get(mdkey)).intValue();

                // Incorrect record length on line log the error. Line will not
                // be included in the
                // dataset
                if (line.length() > recordLength) {
                    addError("LINE TOO LONG. LINE IS " + line.length() + " LONG. SHOULD BE " + recordLength, lineCount, 2);
                    continue;
                } else if (line.length() < recordLength) {
                    if (handleShortLines) {
                        // We can pad this line out
                        line += ParserUtils.padding(recordLength - line.length(), ' ');

                        // log a warning
                        addError("PADDED LINE TO CORRECT RECORD LENGTH", lineCount, 1);

                    } else {
                        addError("LINE TOO SHORT. LINE IS " + line.length() + " LONG. SHOULD BE " + recordLength, lineCount, 2);
                        continue;
                    }
                }

                int recPosition = 1;
                final Row row = new Row();
                row.setMdkey(mdkey.equals(PZConstants.DETAIL_ID) ? null : mdkey); // try

                final List cmds = ParserUtils.getColumnMetaData(mdkey, columnMD);
                // to limit the memory use
                // Build the columns for the row
                for (int i = 0; i < cmds.size(); i++) {
                    final String tempValue = line.substring(recPosition - 1, recPosition
                            + (((ColumnMetaData) cmds.get(i)).getColLength() - 1));
                    recPosition += ((ColumnMetaData) cmds.get(i)).getColLength();
                    row.addColumn(tempValue.trim());
                }
                row.setRowNumber(lineCount);
                // add the row to the array
                rows.add(row);
            }
        } finally {
            if (isr != null) {
                isr.close();
            }
            if (br != null) {
                br.close();
            }
        }
    }

    /*
     * This is the new version of doDelimitedFile using InputStrem instead of
     * File. This is more flexible especially it is working with WebStart.
     * 
     * puts together the dataset for a DELIMITED file. This is used for PZ XML
     * mappings, and SQL table mappings
     */
    private void doDelimitedFile(final InputStream dataSource, final char delimiter, final char qualifier,
            final boolean ignoreFirstRecord, final boolean createMDFromFile) throws Exception {
        if (dataSource == null) {
            throw new NullPointerException("dataSource is null");
        }

        InputStreamReader isr = null;
        BufferedReader br = null;

        try {
            rows = new ArrayList();
            errors = new ArrayList();

            // get the total column count
            // columnCount = columnMD.size();

            /** Read in the flat file */
            // fr = new FileReader(dataSource.getAbsolutePath());
            isr = new InputStreamReader(dataSource);
            br = new BufferedReader(isr);

            boolean processedFirst = false;
            boolean processingMultiLine = false;
            int lineCount = 0;
            String lineData = "";
            /** loop through each line in the file */
            String line = null;
            while ((line = br.readLine()) != null) {
                lineCount++;
                /** empty line skip past it */
                String trimmed = line.trim();
                if (!processingMultiLine && trimmed.length() == 0) {
                    continue;
                }

                // check to see if the user has elected to skip the first record
                if (!processedFirst && ignoreFirstRecord) {
                    processedFirst = true;
                    continue;
                } else if (!processedFirst && createMDFromFile) {
                    processedFirst = true;
                    columnMD = ParserUtils.getColumnMDFromFile(line, delimiter, qualifier);
                    continue;
                }

                // ********************************************************
                // new functionality as of 2.1.0 check to see if we have
                // any line breaks in the middle of the record, this will only
                // be checked if we have specified a delimiter
                // ********************************************************
                final char[] chrArry = trimmed.toCharArray();
                if (!processingMultiLine && delimiter > 0) {
                    processingMultiLine = ParserUtils.isMultiLine(chrArry, delimiter, qualifier);
                }

                // check to see if we have reached the end of the linebreak in
                // the record

                final String trimmedLineData = lineData.trim();
                if (processingMultiLine && trimmedLineData.length() > 0) {
                    // need to do one last check here. it is possible that the "
                    // could be part of the data
                    // excel will escape these with another quote; here is some
                    // data "" This would indicate
                    // there is more to the multiline
                    if (trimmed.charAt(trimmed.length() - 1) == qualifier && !trimmed.endsWith("" + qualifier + qualifier)) {
                        // it is safe to assume we have reached the end of the
                        // line break
                        processingMultiLine = false;
                        if (trimmedLineData.length() > 0) { //+ would always be true surely....
                            lineData += "\r\n";
                        }
                        lineData += line;
                    } else {
                        // check to see if this is the last line of the record
                        // looking for a qualifier followed by a delimiter
                        if (trimmedLineData.length() > 0) { //+ here again, this should always be true...
                            lineData += "\r\n";
                        }
                        lineData += line;
                        boolean qualiFound = false;
                        for (int i = 0; i < chrArry.length; i++) {
                            if (qualiFound) {
                                if (chrArry[i] == ' ') {
                                    continue;
                                } else {
                                    // not a space, if this char is the
                                    // delimiter, then we have reached the end
                                    // of
                                    // the record
                                    if (chrArry[i] == delimiter) {
                                        // processingMultiLine = false;
                                        // fix put in, setting to false caused
                                        // bug when processing multiple
                                        // multi-line
                                        // columns on the same record
                                        processingMultiLine = ParserUtils.isMultiLine(chrArry, delimiter, qualifier);
                                        break;
                                    }
                                    qualiFound = false;
                                    continue;
                                }
                            } else if (chrArry[i] == qualifier) {
                                qualiFound = true;
                            }
                        }
                        // check to see if we are still in multi line mode, if
                        // so grab the next line
                        if (processingMultiLine) {
                            continue;
                        }
                    }
                } else {
                    // throw the line into lineData var.
                    lineData += line;
                    if (processingMultiLine) {
                        continue; // if we are working on a multiline rec, get
                        // the data on the next line
                    }
                }
                // ********************************************************************
                // end record line break logic
                // ********************************************************************

                // column values
                final List columns = ParserUtils.splitLine(lineData, delimiter, qualifier);
                lineData = "";
                final String mdkey = ParserUtils.getCMDKeyForDelimitedFile(columnMD, columns);
                final List cmds = ParserUtils.getColumnMetaData(mdkey, columnMD);
                final int columnCount = cmds.size();
                // DEBUG

                // Incorrect record length on line log the error. Line
                // will not be included in the dataset
                if (columns.size() > columnCount) {
                    // log the error
                    addError("TOO MANY COLUMNS WANTED: " + columnCount + " GOT: " + columns.size(), lineCount, 2);
                    continue;
                } else if (columns.size() < columnCount) {
                    if (handleShortLines) {
                        // We can pad this line out
                        while (columns.size() < columnCount) {
                            columns.add("");
                        }

                        // log a warning
                        addError("PADDED LINE TO CORRECT NUMBER OF COLUMNS", lineCount, 1);

                    } else {
                        addError("TOO FEW COLUMNS WANTED: " + columnCount + " GOT: " + columns.size(), lineCount, 2);
                        continue;
                    }
                }

                final Row row = new Row();
                row.setMdkey(mdkey.equals(PZConstants.DETAIL_ID) ? null : mdkey); // try
                // to limit the memory use
                row.setCols(columns);
                row.setRowNumber(lineCount);
                /** add the row to the array */
                rows.add(row);
            }
        } finally {
            if (isr != null) {
                isr.close();
            }
            if (br != null) {
                br.close();
            }
        }
    }

    /**
     * Changes the value of a specified column in a row in the set. This change
     * is in memory, and does not actually change the data in the file that was
     * read in.
     * 
     * @param columnName -
     *            String Name of the column
     * @param value -
     *            String value to assign to the column.
     * @exception Exception -
     *                exception will be thrown if pointer in not on a valid row
     */
    public void setValue(final String columnName, final String value) throws Exception {
        /** get a reference to the row */
        final Row row = (Row) rows.get(pointer);

        final int idx = ParserUtils.getColumnIndex(row.getMdkey(), columnMD, columnName);
        row.setValue(idx, value);
        // final List cmds = ParserUtils.getColumnMetaData(row.getMdkey(),
        // columnMD);
        /** change the value of the column */
        // row.setValue(ParserUtils.findColumn(columnName, cmds), value);
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#goTop()
     */
    public void goTop() {
        pointer = -1;
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#goBottom()
     */
    public void goBottom() {
        pointer = rows.size() - 1;
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#next()
     */
    public boolean next() {
        if (pointer < rows.size() && pointer + 1 != rows.size()) {
            pointer++;
            return true;
        }
        return false;
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#previous()
     */
    public boolean previous() {
        if (pointer <= 0) {
            return false;
        }
        pointer--;
        return true;
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#getString(java.lang.String)
     */
    public String getString(final String column) {
        final Row row = (Row) rows.get(pointer);
        // final List cmds = ParserUtils.getColumnMetaData(row.getMdkey(),
        // columnMD);
        final String s = row.getValue(ParserUtils.getColumnIndex(row.getMdkey(), columnMD, column));

        if (upperCase) {
            // convert data to uppercase before returning
            // return row.getValue(ParserUtils.findColumn(column,
            // cmds)).toUpperCase(Locale.getDefault());
            return s.toUpperCase(Locale.getDefault());
        }

        if (lowerCase) {
            // convert data to lowercase before returning
            // return row.getValue(ParserUtils.findColumn(column,
            // cmds)).toLowerCase(Locale.getDefault());
            return s.toLowerCase(Locale.getDefault());
        }

        // return value as how it is in the file
        // return row.getValue(ParserUtils.findColumn(column, cmds));
        return s;
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#getDouble(java.lang.String)
     */
    public double getDouble(final String column) {
        final StringBuffer newString = new StringBuffer();
        final Row row = (Row) rows.get(pointer);

        // final List cmds = ParserUtils.getColumnMetaData(row.getMdkey(),
        // columnMD);
        // String s = ((Row)
        // rows.get(pointer)).getValue(ParserUtils.findColumn(column, cmds));
        final String s = row.getValue(ParserUtils.getColumnIndex(row.getMdkey(), columnMD, column));

        if (!strictNumericParse) {
            if (s.trim().length() == 0) {
                return 0;
            }
            for (int i = 0; i < s.length(); i++) {
                final char c = s.charAt(i);
                if (c >= '0' && c <= '9' || c == '.' || c == '-') {
                    newString.append(c);
                }
            }
            if (newString.length() == 0 || (newString.length() == 1 && newString.toString().equals("."))
                    || (newString.length() == 1 && newString.toString().equals("-"))) {
                newString.append("0");
            }
        } else {
            newString.append(s);
        }

        return Double.parseDouble(newString.toString());
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#getInt(java.lang.String)
     */
    public int getInt(final String column) {
        final StringBuffer newString = new StringBuffer();
        final Row row = (Row) rows.get(pointer);
        // final List cmds = ParserUtils.getColumnMetaData(row.getMdkey(),
        // columnMD);
        //
        // String s = row.getValue(ParserUtils.findColumn(column, cmds));
        final String s = row.getValue(ParserUtils.getColumnIndex(row.getMdkey(), columnMD, column));

        if (!strictNumericParse) {
            if (s.trim().length() == 0) {
                return 0;
            }
            for (int i = 0; i < s.length(); i++) {
                final char c = s.charAt(i);
                if (c >= '0' && c <= '9' || c == '-') {
                    newString.append(c);
                }
            }
            // check to make sure we do not have a single length string with
            // just a minus sign
            if (newString.length() == 0 || (newString.length() == 1 && newString.toString().equals("-"))) {
                newString.append("0");
            }
        } else {
            newString.append(s);
        }

        return Integer.parseInt(newString.toString());
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#getDate(java.lang.String)
     */
    public Date getDate(final String column) throws ParseException {
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        final Row row = (Row) rows.get(pointer);
        // final List cmds = ParserUtils.getColumnMetaData(row.getMdkey(),
        // columnMD);

        // String s = row.getValue(ParserUtils.findColumn(column, cmds));
        final String s = row.getValue(ParserUtils.getColumnIndex(row.getMdkey(), columnMD, column));
        return sdf.parse(s);
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#getDate(java.lang.String, java.text.SimpleDateFormat)
     */
    public Date getDate(final String column, final SimpleDateFormat sdf) throws ParseException {
        final Row row = (Row) rows.get(pointer);
        // final List cmds = ParserUtils.getColumnMetaData(row.getMdkey(),
        // columnMD);
        //
        // String s = row.getValue(ParserUtils.findColumn(column, cmds));
        final String s = row.getValue(ParserUtils.getColumnIndex(row.getMdkey(), columnMD, column));
        return sdf.parse(s);
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#getColumns()
     */
    public String[] getColumns() {
        ColumnMetaData column = null;
        String[] array = null;

        if (columnMD != null) {
            final List cmds = ParserUtils.getColumnMetaData(PZConstants.DETAIL_ID, columnMD);

            array = new String[cmds.size()];
            for (int i = 0; i < cmds.size(); i++) {
                column = (ColumnMetaData) cmds.get(i);
                array[i] = column.getColName();
            }
        }

        return array;
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#getColumns(java.lang.String)
     */
    public String[] getColumns(final String recordID) {
        String[] array = null;

        if (columnMD != null) {
            final List cmds = ParserUtils.getColumnMetaData(recordID, columnMD);
            array = new String[cmds.size()];
            for (int i = 0; i < cmds.size(); i++) {
                final ColumnMetaData column = (ColumnMetaData) cmds.get(i);
                array[i] = column.getColName();
            }
        }

        return array;
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#getRowNo()
     */
    public int getRowNo() {
        return ((Row) rows.get(pointer)).getRowNumber();
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#getErrors()
     */
    public List getErrors() {
        return errors;
    }

    /**
     * Adds a new error to this DataSet. These can be collected, and retreived
     * after processing
     * 
     * @param errorDesc -
     *            String description of error
     * @param lineNo -
     *            int line number error occured on
     * @param errorLevel -
     *            int errorLevel 1,2,3 1=warning 2=error 3= severe error
     */
    public void addError(final String errorDesc, final int lineNo, final int errorLevel) {
        final DataError de = new DataError();
        de.setErrorDesc(errorDesc);
        de.setLineNo(lineNo);
        de.setErrorLevel(errorLevel);
        errors.add(de);
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#remove()
     */
    public void remove() {
        rows.remove(pointer);
        pointer--;
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#getIndex()
     */
    public int getIndex() {
        return pointer;
    }

    /**
     * Sets the absolute position of the record pointer
     * 
     * @param localPointer -
     *            int
     * @exception IndexOutOfBoundsException
     */
    public void absolute(final int localPointer) {
        if (localPointer < 0 || localPointer > rows.size() - 1) {
            throw new IndexOutOfBoundsException("INVALID POINTER LOCATION: " + localPointer);
        }

        pointer = localPointer;
    }

    /**
     * Checks to see if the row has the given <RECORD> id
     * 
     * @param recordID
     * @return boolean
     */
    public boolean isRecordID(final String recordID) {
        String rowID = ((Row) rows.get(pointer)).getMdkey();
        if (rowID == null) {
            rowID = PZConstants.DETAIL_ID;
        }

        return rowID.equals(recordID);
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#getRowCount()
     */
    public int getRowCount() {
        return rows.size();
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#getErrorCount()
     */
    public int getErrorCount() {
        if (getErrors() != null) {
            return getErrors().size();
        }

        return 0;
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#isAnError(int)
     */
    public boolean isAnError(final int lineNo) {
        for (int i = 0; i < errors.size(); i++) {
            if (((DataError) errors.get(i)).getLineNo() == lineNo && ((DataError) errors.get(i)).getErrorLevel() > 1) {
                return true;
            }
        }
        return false;
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#orderRows(net.sf.pzfilereader.ordering.OrderBy)
     */
    public void orderRows(final OrderBy ob) throws Exception {
        // PZ try to handle other <records> by sending them to
        // the bottom of the sort
        // if (columnMD.size() > 1) {
        // throw new Exception("orderRows does not currently support ordering
        // with <RECORD> mappings");
        // }
        if (ob != null && rows != null) {
            final List cmds = ParserUtils.getColumnMetaData(PZConstants.DETAIL_ID, columnMD);
            ob.setColumnMD(cmds);
            Collections.sort(rows, ob);
            goTop();
        }
    }

    /**
     * Sets data in the DataSet to lowercase
     */
    public void setLowerCase() {
        upperCase = false;
        lowerCase = true;
    }

    /**
     * Sets data in the DataSet to uppercase
     */
    public void setUpperCase() {
        upperCase = true;
        lowerCase = false;
    }

    /**
     * Setting this to True will parse text as is and throw a
     * NumberFormatException. Setting to false, which is the default, will
     * remove any non numeric charcter from the field. The remaining numeric
     * chars's will be returned. If it is an empty string,or there are no
     * numeric chars, 0 will be returned for getInt() and getDouble()
     * 
     * @param strictNumericParse
     *            The strictNumericParse to set.
     */
    public void setStrictNumericParse(final boolean strictNumericParse) {
        this.strictNumericParse = strictNumericParse;
    }

    /**
     * Erases the dataset early and releases memory for the JVM to reclaim, this
     * invalidates the object.
     * 
     * @deprecated You can still use it but truly you should keep the scope of
     *             the DataSet to a MINIMUM.
     */
    public void freeMemory() {
        if (rows != null) {
            rows.clear();
        }
        if (errors != null) {
            errors.clear();
        }
        if (columnMD != null) {
            columnMD.clear();
        }
    }

    /**
     * Writes this current DataSet out to the specified Excel file
     * 
     * @param excelFileToBeWritten
     * @exception Exception
     */
    public void writeToExcel(final File excelFileToBeWritten) throws Exception {
        final ExcelTransformer et = new ExcelTransformer(this, excelFileToBeWritten);
        et.writeExcelFile();
    }

    /**
     * Returns the version number of this pzFileReader
     * 
     * @return String
     */
    public String getReaderVersion() {
        return Version.VERSION;
    }

    /**
     * @return Returns the handleShortLines.
     */
    public boolean isHandleShortLines() {
        return handleShortLines;
    }

    /**
     * This is used for LargeDataSet compatability. Setting this will have no
     * affect on the DataSet parser. It must be passed on the constructor
     * 
     * @param handleShortLines
     *            The handleShortLines to set.
     */
    public void setHandleShortLines(final boolean handleShortLines) {
        this.handleShortLines = handleShortLines;
    }

    public Map getColumnMD() {
        return columnMD;
    }

    public void setColumnMD(final Map columnMD) {
        this.columnMD = columnMD;
    }

    /* (non-Javadoc)
     * @see net.sf.pzfilereader.IDataSet#getRows()
     */
    public List getRows() {
        return rows;
    }

    public void setRows(final List rows) {
        this.rows = rows;
    }

    public void setErrors(final List errors) {
        this.errors = errors;
    }
}
