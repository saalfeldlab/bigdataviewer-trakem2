package bdv.img.trakem2;

import java.awt.Rectangle;

import bdv.AbstractViewerSetupImgLoader;
import bdv.ViewerImgLoader;
import bdv.ViewerSetupImgLoader;
import bdv.img.cache.CacheHints;
import bdv.img.cache.CachedCellImg;
import bdv.img.cache.LoadingStrategy;
import bdv.img.cache.VolatileGlobalCellCache;
import bdv.img.cache.VolatileImgCells;
import bdv.img.cache.VolatileImgCells.CellCache;
import ij.measure.Calibration;
import ini.trakem2.Project;
import ini.trakem2.display.LayerSet;
import ini.trakem2.persistence.Loader;
import mpicbg.spim.data.generic.sequence.ImgLoaderHint;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.NativeImg;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileIntArray;
import net.imglib2.img.cell.CellImg;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.volatiles.VolatileARGBType;
import net.imglib2.util.Fraction;

public class TrakEM2ImageLoader extends AbstractViewerSetupImgLoader< ARGBType, VolatileARGBType > implements ViewerImgLoader
{
	final private Loader loader;

	final protected int setupId;

	private int width;
	private int height;
	private int depth;

	private int tileWidth;
	private int tileHeight;

	private double zScale;

	private int numScales;

	private double[][] mipmapResolutions;

	private AffineTransform3D[] mipmapTransforms;

	private long[][] imageDimensions;

	private int[] zScales;

	private VolatileGlobalCellCache cache;

	final protected TrakEM2VolatileIntArrayLoader arrayLoader;

	final static public int getNumScales(
			long width,
			long height,
			final long tileWidth,
			final long tileHeight )
	{
		int i = 1;

		while ( ( width >>= 1 ) > tileWidth && ( height >>= 1 ) > tileHeight )
			++i;

		return i;
	}

	public TrakEM2ImageLoader(
			final Project project,
			final LayerSet layerset,
			final int setupId,
			final int tileWidth,
			final int tileHeight,
			final int numScales,
			final boolean averageSlices )
	{
		super( new ARGBType(), new VolatileARGBType() );

		this.setupId = setupId;

		this.tileWidth = tileWidth;
		this.tileHeight = tileHeight;
		this.numScales = numScales;

		loader = project.getLoader();
		layerset.setSnapshotsMode(1);
		final Rectangle box = layerset.get2DBounds();

		width = box.width;
		height = box.height;
		depth = layerset.getLayers().size();

		final Calibration calibration = layerset.getCalibration();
		zScale = calibration.pixelDepth / calibration.pixelWidth;

		mipmapResolutions = new double[ numScales ][];
		imageDimensions = new long[ numScales ][];
		mipmapTransforms = new AffineTransform3D[ numScales ];
		zScales = new int[ numScales ];
		for ( int l = 0; l < numScales; ++l )
		{
			final int sixy = 1 << l;
			final int siz = averageSlices ? Math.max( 1, ( int )Math.round( sixy / zScale ) ) : 1;

			mipmapResolutions[ l ] = new double[] { sixy, sixy, siz };
			imageDimensions[ l ] = new long[] { width >> l, height >> l, depth / siz };
			zScales[ l ] = siz;

			final AffineTransform3D mipmapTransform = new AffineTransform3D();

			mipmapTransform.set( sixy, 0, 0 );
			mipmapTransform.set( sixy, 1, 1 );
			mipmapTransform.set( zScale * siz, 2, 2 );

			mipmapTransform.set( 0.5 * ( sixy - 1 ), 0, 3 );
			mipmapTransform.set( 0.5 * ( sixy - 1 ), 1, 3 );
			mipmapTransform.set( 0.5 * ( zScale * siz - 1 ), 2, 3 );

			mipmapTransforms[ l ] = mipmapTransform;
		}

		cache = new VolatileGlobalCellCache( 1, 1, 1, 10 );

		arrayLoader = new TrakEM2VolatileIntArrayLoader( loader, layerset, zScales );
	}

	public TrakEM2ImageLoader(
			final Project project,
			final LayerSet layerset,
			final int setupId,
			final int tileWidth,
			final int tileHeight,
			final boolean averageSlices )
	{
		this(
				project,
				layerset,
				setupId,
				tileWidth,
				tileHeight,
				getNumScales(
						( long )Math.ceil( layerset.get2DBounds().getWidth() ),
						( long )Math.ceil( layerset.get2DBounds().getHeight() ),
						tileWidth,
						tileHeight ),
				averageSlices );
	}

	@Override
	public RandomAccessibleInterval< ARGBType > getImage( final int timepointId, final int level, final ImgLoaderHint... hints )
	{
		final CachedCellImg< ARGBType, VolatileIntArray >  img = prepareCachedImage( timepointId, level, LoadingStrategy.BLOCKING );
		final ARGBType linkedType = new ARGBType( img );
		img.setLinkedType( linkedType );
		return img;
	}

	@Override
	public RandomAccessibleInterval< VolatileARGBType > getVolatileImage( final int timepointId, final int level, final ImgLoaderHint... hints )
	{
		final CachedCellImg< VolatileARGBType, VolatileIntArray >  img = prepareCachedImage( timepointId, level, LoadingStrategy.VOLATILE );
		final VolatileARGBType linkedType = new VolatileARGBType( img );
		img.setLinkedType( linkedType );
		return img;
	}

	@Override
	public ViewerSetupImgLoader< ?, ? > getSetupImgLoader( final int setupId )
	{
		return this;
	}

	@Override
	public double[][] getMipmapResolutions()
	{
		return mipmapResolutions;
	}

	@Override
	public int numMipmapLevels()
	{
		return numScales;
	}

	/**
	 * (Almost) create a {@link CellImg} backed by the cache.
	 * The created image needs a {@link NativeImg#setLinkedType(net.imglib2.type.Type) linked type} before it can be used.
	 * The type should be either {@link ARGBType} and {@link VolatileARGBType}.
	 */
	protected < T extends NativeType< T > > CachedCellImg< T, VolatileIntArray > prepareCachedImage(
			final int timepointId,
			final int level,
			final LoadingStrategy loadingStrategy )
	{
		final long[] dimensions = imageDimensions[ level ];
		final int[] cellDimensions = new int[]{ tileWidth, tileHeight, 1 };

		final int priority = 0;
		final CacheHints cacheHints = new CacheHints( loadingStrategy, priority, false );
		final CellCache< VolatileIntArray > c = cache.new VolatileCellCache< VolatileIntArray >( timepointId, setupId, level, cacheHints, arrayLoader );
		final VolatileImgCells< VolatileIntArray > cells = new VolatileImgCells< VolatileIntArray >( c, new Fraction(), dimensions, cellDimensions );
		final CachedCellImg< T, VolatileIntArray > img = new CachedCellImg< T, VolatileIntArray >( cells );
		return img;
	}

	@Override
	public VolatileGlobalCellCache getCache()
	{
		return cache;
	}

	@Override
	public AffineTransform3D[] getMipmapTransforms()
	{
		return mipmapTransforms;
	}
}
