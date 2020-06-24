package fiji.plugin.trackmate.importer.roicsv;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.scijava.util.VersionUtils;

import fiji.plugin.trackmate.Logger;
import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.SpotRoi;
import fiji.plugin.trackmate.detection.ManualDetectorFactory;
import fiji.plugin.trackmate.exporter.csv.CSVMetadata;
import fiji.plugin.trackmate.exporter.csv.ImporterUtils;
import fiji.plugin.trackmate.features.edges.EdgeAnalyzer;
import fiji.plugin.trackmate.features.edges.EdgeTargetAnalyzer;
import fiji.plugin.trackmate.features.edges.EdgeTimeLocationAnalyzer;
import fiji.plugin.trackmate.features.edges.EdgeVelocityAnalyzer;
import fiji.plugin.trackmate.features.manual.ManualEdgeColorAnalyzer;
import fiji.plugin.trackmate.features.manual.ManualSpotColorAnalyzerFactory;
import fiji.plugin.trackmate.features.spot.SpotAnalyzerFactory;
import fiji.plugin.trackmate.features.track.TrackAnalyzer;
import fiji.plugin.trackmate.features.track.TrackDurationAnalyzer;
import fiji.plugin.trackmate.features.track.TrackIndexAnalyzer;
import fiji.plugin.trackmate.features.track.TrackLocationAnalyzer;
import fiji.plugin.trackmate.features.track.TrackSpeedStatisticsAnalyzer;
import fiji.plugin.trackmate.features.track.TrackSpotQualityFeatureAnalyzer;
import fiji.plugin.trackmate.gui.TrackMateGUIModel;
import fiji.plugin.trackmate.io.TmXmlWriter;
import fiji.plugin.trackmate.providers.EdgeAnalyzerProvider;
import fiji.plugin.trackmate.providers.SpotAnalyzerProvider;
import fiji.plugin.trackmate.providers.TrackAnalyzerProvider;
import fiji.plugin.trackmate.tracking.ManualTrackerFactory;
import ij.ImagePlus;

public class TrackMateCsvRoiImporter
{
	private static final DateFormat DATE_FORMAT = new SimpleDateFormat( "yyyy-MM-dd --- HH:mm:ss" );

	private String errorMessage;

	private final Logger logger;

	private final String csvFilePath;

	private final ImagePlus imp;

	private final String imageFilePath;

	private final boolean declareAllFeatures;

	private TrackMateCsvRoiImporter(
			final String csvFilePath,
			final ImagePlus imp,
			final String imageFilePath,
			final boolean declareAllFeatures,
			final Logger logger )
	{
		this.csvFilePath = csvFilePath;
		this.imp = imp;
		this.imageFilePath = imageFilePath;
		this.declareAllFeatures = declareAllFeatures;
		this.logger = logger;
	}

	public boolean saveTo( final File targetFile )
	{
		final StringBuilder errorHolder = new StringBuilder();

		final Settings settings = getSettings();
		if ( null == settings )
			return false;

		String spaceUnits;
		String timeUnits;
		if ( null == imp )
		{
			final String[] units = ImporterUtils.getUnitsFromImageFile( imageFilePath, errorHolder );
			if ( null == units )
			{
				errorMessage = errorHolder.toString();
				return false;
			}
			spaceUnits = units[ 0 ];
			timeUnits = units[ 1 ];
		}
		else
		{
			spaceUnits = imp.getCalibration().getUnit();
			timeUnits = imp.getCalibration().getTimeUnit();
		}

		final double frameInterval = settings.dt;
		final Model model = getModel( frameInterval, spaceUnits, timeUnits );
		if ( null == model )
			return false;

		final TmXmlWriter writer = new TmXmlWriter( targetFile, logger );

		final String log = "Imported from CSV file "
				+ csvFilePath + '\n'
				+ "On the " + DATE_FORMAT.format( new Date() ) + '\n'
				+ "By TrackMate ROI CSV Importer v" + VersionUtils.getVersion( TrackMateCsvRoiImporter.class ) + '\n';
		writer.appendLog( log );
		writer.appendModel( model );
		writer.appendSettings( settings );
		writer.appendGUIState( new TrackMateGUIModel()
		{
			@Override
			public String getGUIStateString()
			{
				// Because we have no track.
				final String guiState = "SpotFilter";
				return guiState;
			}
		} );

		try
		{
			writer.writeToFile();
			logger.log( "Data saved to: " + targetFile.toString() + '\n' );
		}
		catch ( final FileNotFoundException e )
		{
			final String str = "File not found:\n" + e.getMessage() + '\n';
			logger.error( str );
			errorMessage = str;

			return false;
		}
		catch ( final IOException e )
		{
			final String str = "Input/Output error:\n" + e.getMessage() + '\n';
			logger.error( str );
			errorMessage = str;
			return false;
		}
		return true;
	}

	public Settings getSettings()
	{
		final StringBuilder errorHolder = new StringBuilder();
		final Settings settings = ( imp == null )
				? ImporterUtils.createSettingsFromImageFile( imageFilePath, errorHolder, logger )
				: ImporterUtils.createSettingsFromImp( imp, logger );

		if ( null == settings )
		{
			errorMessage = errorHolder.toString();
			return null;
		}
		settings.detectorFactory = new ManualDetectorFactory<>();
		settings.detectorSettings = settings.detectorFactory.getDefaultSettings();
		settings.trackerFactory = new ManualTrackerFactory();
		settings.trackerSettings = settings.trackerFactory.getDefaultSettings();

		if ( declareAllFeatures )
		{

			settings.clearSpotAnalyzerFactories();
			final SpotAnalyzerProvider spotAnalyzerProvider = new SpotAnalyzerProvider();
			final List< String > spotAnalyzerKeys = spotAnalyzerProvider.getKeys();
			for ( final String key : spotAnalyzerKeys )
			{
				final SpotAnalyzerFactory< ? > spotFeatureAnalyzer = spotAnalyzerProvider.getFactory( key );
				settings.addSpotAnalyzerFactory( spotFeatureAnalyzer );
			}

			settings.clearEdgeAnalyzers();
			final EdgeAnalyzerProvider edgeAnalyzerProvider = new EdgeAnalyzerProvider();
			final List< String > edgeAnalyzerKeys = edgeAnalyzerProvider.getKeys();
			for ( final String key : edgeAnalyzerKeys )
			{
				final EdgeAnalyzer edgeAnalyzer = edgeAnalyzerProvider.getFactory( key );
				settings.addEdgeAnalyzer( edgeAnalyzer );
			}

			settings.clearTrackAnalyzers();
			final TrackAnalyzerProvider trackAnalyzerProvider = new TrackAnalyzerProvider();
			final List< String > trackAnalyzerKeys = trackAnalyzerProvider.getKeys();
			for ( final String key : trackAnalyzerKeys )
			{
				final TrackAnalyzer trackAnalyzer = trackAnalyzerProvider.getFactory( key );
				settings.addTrackAnalyzer( trackAnalyzer );
			}
		}
		else
		{
			/*
			 * Minimal set of features.
			 */

			// Spot features.
			settings.addSpotAnalyzerFactory( new ManualSpotColorAnalyzerFactory<>() );

			// Edge features.
			settings.addEdgeAnalyzer( new EdgeTargetAnalyzer() );
			settings.addEdgeAnalyzer( new EdgeTimeLocationAnalyzer() );
			settings.addEdgeAnalyzer( new EdgeVelocityAnalyzer() );
			settings.addEdgeAnalyzer( new ManualEdgeColorAnalyzer() );

			// Track features.
			settings.addTrackAnalyzer( new TrackDurationAnalyzer() );
			settings.addTrackAnalyzer( new TrackIndexAnalyzer() );
			settings.addTrackAnalyzer( new TrackLocationAnalyzer() );
			settings.addTrackAnalyzer( new TrackSpeedStatisticsAnalyzer() );
			settings.addTrackAnalyzer( new TrackSpotQualityFeatureAnalyzer() );
		}

		logger.log( "Added the following features to be computed:\n" + settings.toStringFeatureAnalyzersInfo() );
		return settings;
	}

	public Model getModel( final double frameInterval, final String spaceUnit, final String timeUnit )
	{
		@SuppressWarnings( "unused" )
		CSVMetadata csvMetadata = null;
		try
		{
			csvMetadata = CSVMetadata.parse( csvFilePath );
		}
		catch ( final IOException e )
		{
			errorMessage = "problem parsing CSV metadata:\n" + e.getMessage();
			e.printStackTrace();
			return null;
		}

		/*
		 * Prepare spot & track Collections.
		 */

		final Map< Integer, Set< Spot > > spots = new HashMap<>();

		/*
		 * Open and parse file.
		 */

		Reader in;
		CSVParser records;
		try
		{
			in = new FileReader( csvFilePath );
		}
		catch ( final FileNotFoundException e )
		{
			e.printStackTrace();
			errorMessage = e.getMessage();
			return null;
		}

		try
		{
			final CSVFormat csvFormat = CSVFormat.EXCEL
					.withCommentMarker( '#' );
			records = csvFormat.parse( in );
		}
		catch ( final IOException e )
		{
			e.printStackTrace();
			errorMessage = "Problem accessing file " + csvFilePath + ":\n" + e.getMessage();
			return null;
		}

		/*
		 * Get the number of lines to have a crude estimate of how many records
		 * we have to parse.
		 */

		final long nLines = ImporterUtils.countLineNumber( csvFilePath );

		/*
		 * Iterate over records.
		 */

		logger.log( String.format( "Parsing records.\n" ) );
		long nRecords = 0;
		for ( final CSVRecord record : records )
		{
			logger.setProgress( ( double ) nRecords / nLines );
			nRecords++;
			try
			{
				final int nVertices = ( record.size() - 2 ) / 2;
				if ( nVertices < 3 )
				{
					logger.log( " Could not parse line " + record.getRecordNumber() + ". Spot ROI has " + nVertices + " vertices, at least 3 is required.\n" );
					continue;
				}

				final Iterator< String > it = record.iterator();

				// Label.
				final String label = it.next();

				// Frame.
				final int t = Integer.parseInt( it.next() );

				// ROI.
				final double[] xpoly = new double[ nVertices ];
				final double[] ypoly = new double[ nVertices ];
				int index = 0;
				while ( it.hasNext() )
				{
					final double x = Double.parseDouble( it.next() );
					final double y = Double.parseDouble( it.next() );
					xpoly[ index ] = x;
					ypoly[ index ] = y;
					index++;
				}

				// Quality.
				final double quality = 1.;

				// Create Spot.
				final Spot spot = SpotRoi.createSpot( xpoly, ypoly, quality );
				spot.setName( label );
				spot.putFeature( Spot.FRAME, ( double ) t );
				spot.putFeature( Spot.POSITION_T, frameInterval * t );

				// Add it to current list.
				Set< Spot > list = spots.get( Integer.valueOf( t ) );
				if ( null == list )
				{
					list = new HashSet<>();
					spots.put( Integer.valueOf( t ), list );
				}
				list.add( spot );

			}
			catch ( final NumberFormatException nfe )
			{
				logger.log( " Could not parse line " + record.getRecordNumber() + ". Malformed number, skipping.\n" + nfe.getMessage() );
				continue;
			}
		}
		logger.log( String.format( "Parsing done. Iterated over %d records.\n", nRecords ) );

		/*
		 * Generate a Model object.
		 */

		final SpotCollection sc = SpotCollection.fromMap( spots );
		sc.setVisible( true );
		logger.log( String.format( "Found %d spots.\n", sc.getNSpots( true ) ) );

		final NavigableSet< Integer > frames = sc.keySet();
		for ( final Integer frame : frames )
			logger.log( String.format( "- frame %4d, n spots = %d\n", frame, sc.getNSpots( frame, true ) ) );

		final Model model = new Model();
		model.setPhysicalUnits( spaceUnit, timeUnit );
		model.setLogger( logger );
		model.setSpots( sc, false );

		logger.setProgress( 0. );
		return model;
	}

	public String getErrorMessage()
	{
		return errorMessage;
	}

	public static Builder builder()
	{
		return new Builder();
	}

	public static final class Builder
	{

		private static class Values
		{
			private String csvFilePath;

			private String imageFilePath;

			private ImagePlus imp;

			private Logger logger = Logger.DEFAULT_LOGGER;

			private boolean declareAllFeatures = true;
		}

		private final Values values;

		private Builder()
		{
			this.values = new Values();
		}

		public Builder csvFilePath( final String csvFilePath )
		{
			values.csvFilePath = csvFilePath;
			return this;
		}

		public Builder imageFilePath( final String imageFilePath )
		{
			values.imageFilePath = imageFilePath;
			return this;
		}

		public Builder imp( final ImagePlus imp )
		{
			values.imp = imp;
			return this;
		}

		public Builder declareAllFeatures( final boolean declareAllFeatures )
		{
			values.declareAllFeatures = declareAllFeatures;
			return this;
		}

		public Builder logger( final Logger logger )
		{
			values.logger = logger;
			return this;
		}

		public TrackMateCsvRoiImporter create()
		{
			return new TrackMateCsvRoiImporter(
					values.csvFilePath,
					values.imp,
					values.imageFilePath,
					values.declareAllFeatures,
					values.logger );
		}
	}
}