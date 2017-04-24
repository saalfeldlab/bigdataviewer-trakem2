package bdv.ij;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

import bdv.BigDataViewer;
import bdv.ij.util.ProgressWriterIJ;
import bdv.img.trakem2.TrakEM2ImageLoader;
import bdv.spimdata.SequenceDescriptionMinimal;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.WrapBasicImgLoader;
import bdv.tools.brightness.SetupAssignments;
import bdv.viewer.ViewerOptions;
import bdv.viewer.VisibilityAndGrouping;
import ij.IJ;
import ij.ImageJ;
import ij.plugin.PlugIn;
import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.display.Display;
import ini.trakem2.display.LayerSet;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import net.imglib2.FinalDimensions;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * ImageJ plugin to show the current TrakEM2 project in BigDataViewer.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class OpenTrakEM2ProjectPlugIn implements PlugIn
{
	public static void main( final String... args )
	{
		final ImageJ ij = new ImageJ();

		final Project project = Project.openFSProject( "/data/saalfeld/trakem2/c.elastic.intensity-per-montage/untitled.xml" );

		new OpenTrakEM2ProjectPlugIn().run("");
	}

	@Override
	public void run( final String arg )
	{
		final Project project = ControlWindow.getActive();
		final Display front = Display.getFront();

		final LayerSet layerset;
		if ( front == null )
		{
			if ( project == null )
			{
				IJ.showMessage( "Please open a TrakEM2 project." );
				return;
			}
			layerset = project.getRootLayerSet();
		}
		else
		{
			layerset = front.getLayerSet();
		}

		if ( layerset == null )
		{
			IJ.showMessage( "Could not find a layerset for display.  Please add a layerset to the project" );
			return;
		}

		final double pw = layerset.getCalibration().pixelWidth;
		final double ph = layerset.getCalibration().pixelHeight;
		final double pd = layerset.getCalibration().pixelDepth;
		String punit = layerset.getCalibration().getUnit();
		if ( punit == null || punit.isEmpty() )
			punit = "px";
		final FinalVoxelDimensions voxelSize = new FinalVoxelDimensions( punit, pw, ph, pd );
		final int w = ( int )Math.ceil( layerset.get2DBounds().getWidth() );
		final int h = ( int )Math.ceil( layerset.get2DBounds().getHeight() );
		final int d = ( int )Math.ceil( layerset.getLayers().size() );

		final FinalDimensions size = new FinalDimensions( new int[] { w, h, d } );

		final TrakEM2ImageLoader imgLoader = new TrakEM2ImageLoader(
				project,
				layerset,
				( int )project.getId(),
				64,
				64,
				false );

		// get calibration and image size

		final int numTimepoints = 1;
		final int numSetups = 1;

		// create setups from channels
		final HashMap< Integer, BasicViewSetup > setups = new HashMap< Integer, BasicViewSetup >( numSetups );
		for ( int s = 0; s < numSetups; ++s )
		{
			final BasicViewSetup setup = new BasicViewSetup( s, String.format( "channel %d", s + 1 ), size, voxelSize );
			setup.setAttribute( new Channel( s + 1 ) );
			setups.put( s, setup );
		}

		// create timepoints
		final ArrayList< TimePoint > timepoints = new ArrayList< TimePoint >( numTimepoints );
		for ( int t = 0; t < numTimepoints; ++t )
			timepoints.add( new TimePoint( t ) );
		final SequenceDescriptionMinimal seq = new SequenceDescriptionMinimal( new TimePoints( timepoints ), setups, imgLoader, null );

		// create ViewRegistrations from the images calibration
		final AffineTransform3D sourceTransform = new AffineTransform3D();
		//sourceTransform.set( 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 );
		final ArrayList< ViewRegistration > registrations = new ArrayList< ViewRegistration >();
		for ( int t = 0; t < numTimepoints; ++t )
			for ( int s = 0; s < numSetups; ++s )
				registrations.add( new ViewRegistration( t, s, sourceTransform ) );

		final File basePath = new File(".");
		final SpimDataMinimal spimData = new SpimDataMinimal( basePath, seq, new ViewRegistrations( registrations ) );
		WrapBasicImgLoader.wrapImgLoaderIfNecessary( spimData );

		final BigDataViewer bdv = BigDataViewer.open( spimData, "BigDataViewer", new ProgressWriterIJ(), ViewerOptions.options() );
		final SetupAssignments sa = bdv.getSetupAssignments();
		final VisibilityAndGrouping vg = bdv.getViewer().getVisibilityAndGrouping();

		final AffineTransform3D transform = new AffineTransform3D();
		transform.set(
				1, 0, 0, -( w - bdv.getViewerFrame().getWidth() ) / 2,
				0, 1, 0, -( h - bdv.getViewerFrame().getHeight() ) / 2,
				0, 0, 1, -d / 2 * pd / pw );
		System.out.println( transform );
		bdv.getViewer().setCurrentViewerTransform( transform );

	}
}
