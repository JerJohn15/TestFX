/*
 * Copyright 2013 SmartBear Software
 * 
 * Licensed under the EUPL, Version 1.1 or - as soon they will be approved by the European Commission - subsequent
 * versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 * 
 * http://ec.europa.eu/idabc/eupl
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the Licence is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the Licence for the specific language governing permissions and limitations
 * under the Licence.
 */
package org.loadui.testfx;

import org.loadui.testfx.exceptions.NoNodesFoundException;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.*;
import com.google.common.util.concurrent.SettableFuture;
import javafx.application.Application;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SceneBuilder;
import javafx.scene.control.Labeled;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.hamcrest.Matcher;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.getFirst;
import static com.google.common.collect.Iterables.transform;

public class GuiTest
{
	private static final SettableFuture<Stage> stageFuture = SettableFuture.create();
	protected static Stage stage;
	private static String stylesheet = null;

	public static class TestFxApp extends Application
	{
		public static Parent node;

		@Override
		public void start( Stage primaryStage ) throws Exception
		{
			Scene scene = SceneBuilder
					.create()
					.width( 600 )
					.height( 400 )
					.root( node ).build();

			if( stylesheet != null )
				scene.getStylesheets().add( stylesheet );

			primaryStage.setScene( scene );
			primaryStage.show();
			stageFuture.set( primaryStage );
		}
	}

    /**
     * Creates and displays a new stage, with the provided node as root node.
     *
     * @param node
     */
	public static void showNodeInStage( Parent node )
	{
		showNodeInStage( node, null );
	}

	public static void showNodeInStage( Parent node, String stylesheet )
	{
		GuiTest.stylesheet = stylesheet;
		TestFxApp.node = node;
		FXTestUtils.launchApp( TestFxApp.class );
		try
		{
			stage = targetWindow( stageFuture.get( 25, TimeUnit.SECONDS ) );
			FXTestUtils.bringToFront( stage );
		} catch( Exception e )
		{
			throw new RuntimeException( "Unable to show stage", e );
		}
	}



	@Deprecated
	public static GuiTest wrap( ScreenController controller )
	{
		return new GuiTest();
	}

	private static Window lastSeenWindow = null;

	public static <T extends Window> T targetWindow( T window )
	{
		if( window instanceof Stage )
		{
			Stage stage = (Stage) window;
			stage.toFront();
		}
		lastSeenWindow = window;

		return window;
	}

	public static OffsetTarget offset( Object target, double offsetX, double offsetY )
	{
		return new OffsetTarget( target, offsetX, offsetY );
	}

	@SuppressWarnings("deprecation")
	public static List<Window> getWindows()
	{
		return Lists.reverse( Lists.newArrayList( Window.impl_getWindows() ) );
	}

	public static Window getWindowByIndex( int index )
	{
		return getWindows().get( index );
	}

	public static Stage findStageByTitle( final String titleRegex )
	{
		return Iterables.find( Iterables.filter( getWindows(), Stage.class ), new Predicate<Stage>()
		{
			@Override
			public boolean apply( Stage input )
			{
				return input.getTitle().matches( titleRegex );
			}
		} );
	}

	public static Set<Node> findAll( String selector, Object parent )
	{
		try
		{
			if( parent instanceof String )
			{
				final String titleRegex = (String) parent;
				return findAll( selector, targetWindow( findStageByTitle( titleRegex ) ).getScene() );
			} else if( parent instanceof Node )
			{
				Node node = (Node) parent;
				targetWindow( node.getScene().getWindow() );
				return node.lookupAll( selector );
			} else if( parent instanceof Scene )
			{
				Scene scene = (Scene) parent;
				targetWindow( scene.getWindow() );
				return findAll( selector, scene.getRoot() );
			} else if( parent instanceof Window )
			{
				return findAll( selector, targetWindow( (Window) parent ).getScene() );
			}
		} catch( Exception e )
		{
			//Ignore, something went wrong with checking a window, so return an empty set.
		}

		return Collections.emptySet();
	}

	public static Set<Node> findAll( String selector )
	{
		Set<Node> results = Sets.newLinkedHashSet();
		results.addAll( findAll( selector, lastSeenWindow ) );
		final Predicate<Window> isDescendant = new Predicate<Window>()
		{
			@Override
			public boolean apply( Window input )
			{
				Window parent = null;
				if( input instanceof Stage )
				{
					parent = ((Stage) input).getOwner();
				} else if( input instanceof PopupWindow )
				{
					parent = ((PopupWindow) input).getOwnerWindow();
				}

				return parent == lastSeenWindow || parent != null && apply( parent );
			}
		};
		Iterable<Window> descendants = Iterables.filter( getWindows(), isDescendant );
		Iterable<Window> rest = Iterables.filter( getWindows(), Predicates.not( isDescendant ) );
		for( Window descendant : concat( descendants, rest ) )
		{
			results.addAll( findAll( selector, descendant ) );
		}

		return results;
	}

	@SuppressWarnings("unchecked")
	public static <T extends Node> T find( String selector, Object parent )
	{
		return checkNotNull( (T) getFirst( findAll( selector, parent ), null ),
				"Query [%s] select [%s] resulted in no nodes found!", parent, selector );
	}

	@SuppressWarnings("unchecked")
	public static <T extends Node> T find( final String query )
	{
		T nodeFoundByCss = findByCssSelector( query );
		if( nodeFoundByCss != null )
			return nodeFoundByCss;

		T foundNode = (T) find( new HasLabel( query ) );
		if( foundNode == null )
		{
			throw new NoNodesFoundException( "Query "+query+" resulted in no nodes found! Screenshot saved as " + captureScreenshot().getAbsolutePath() );
		}

		return foundNode;
	}

    /**
     * Returns a Callable that calculates the number of nodes that matches the given query.
     *
     * @param nodeQuery a CSS query or the label of a node.
     * @return
     */
	public static Callable<Integer> numberOf( final String nodeQuery )
	{
		 return new Callable<Integer>()
		 {
			 @Override
			 public Integer call() throws Exception
			 {
				 return findAll( nodeQuery ).size();
			 }
		 };
	}

	private static File captureScreenshot()
	{
		File screenshot = new File( "screenshot"+new Date().getTime()+".png" );
		BufferedImage image = null;
		try
		{
			image = new Robot().createScreenCapture( new Rectangle( Toolkit.getDefaultToolkit().getScreenSize() ) );
			ImageIO.write( image, "png", screenshot );
		} catch( Exception e )
		{
			e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
		}
		return screenshot;
	}

	private static class HasLabel<Node> implements Predicate<Node>
	{
		private final String label;

		HasLabel( String label )
		{
			this.label = label;
		}

		@Override
		public boolean apply( Node node )
		{
			return (node instanceof Labeled) && label.equals( ((Labeled) node).getText() );
		}
	};

	public static void waitUntil( final Node node, final Matcher<Object> condition, int timeoutInSeconds )
	{
		TestUtils.awaitCondition( new Callable<Boolean>()
		{
			@Override
			public Boolean call() throws Exception
			{
				return condition.matches( node );
			}
		}, timeoutInSeconds );
	}

    /**
     * Waits until the provided node fulfills the given condition.
     *
     * @param node
     * @param condition
     */
	public static void waitUntil( final Node node, final Matcher<Object> condition )
	{
		waitUntil( node, condition, 15 );
	}

	public static <T> void waitUntil( final T value, final Matcher<? super T> condition )
	{
		waitUntil( value, condition, 15 );
	}

	public static <T> void waitUntil( final Callable<T> callable, final Matcher<? super T> condition )
	{
		TestUtils.awaitCondition( new Callable<Boolean>()
		{
			@Override
			public Boolean call() throws Exception
			{
				return condition.matches( callable.call() );
			}
		}, 15 );
	}

	public static <T> void waitUntil( final T value, final Matcher<? super T> condition, int timeoutInSeconds )
	{
		TestUtils.awaitCondition( new Callable<Boolean>()
		{
			@Override
			public Boolean call() throws Exception
			{
				return condition.matches( value );
			}
		}, timeoutInSeconds );
	}

	private static <T extends Node> T findByCssSelector( final String selector )
	{
		Set<Node> locallyFound = findAll( selector );
		Iterable<Node> globallyFound = concat( transform( getWindows(),
				new Function<Window, Iterable<Node>>()
				{
					@Override
					public Iterable<Node> apply( Window input )
					{
						return findAll( selector, input );
					}
				} ) );

		return (T) getFirst( locallyFound, getFirst( globallyFound, null ) );
	}

	@Deprecated
	@SuppressWarnings("unchecked")
	public static <T extends Node> T find( final Predicate<Node> predicate )
	{
		Iterable<Node> globallyFound = concat( transform( getWindows(),
				new Function<Window, Iterable<Node>>()
				{
					@Override
					public Iterable<Node> apply( Window input )
					{
						return findAll( predicate, input.getScene().getRoot() );
					}
				} ) );

		return (T) getFirst( globallyFound, null );
	}

	public static <T extends Node> T find( final Matcher<Node> matcher )
	{
		Iterable<Node> globallyFound = concat( transform( getWindows(),
				new Function<Window, Iterable<Node>>()
				{
					@Override
					public Iterable<Node> apply( Window input )
					{
						return findAll( matcher, input.getScene().getRoot() );
					}
				} ) );

		return (T) getFirst( globallyFound, null );
	}

    /**
     * Returns all child nodes of parent, that matches the given matcher.
     *
     * @param matcher
     * @param parent
     * @return found nodes
     */
	public static Iterable<Node> findAll( Matcher<Node> matcher, Node parent )
	{
		ImmutableList.Builder<Iterable<Node>> found = ImmutableList.builder();
		if( matcher.matches( parent ) )
		{
			found.add( Collections.singleton( parent ) );
		}
		if( parent instanceof Parent )
		{
			for( Node child : ((Parent) parent).getChildrenUnmodifiable() )
			{
				found.add( findAll( matcher, child ) );
			}
		}

		return concat( found.build() );
	}

	public static Iterable<Node> findAll( Predicate<Node> predicate, Node parent )
	{
		ImmutableList.Builder<Iterable<Node>> found = ImmutableList.builder();
		if( predicate.apply( parent ) )
		{
			found.add( Collections.singleton( parent ) );
		}
		if( parent instanceof Parent )
		{
			for( Node child : ((Parent) parent).getChildrenUnmodifiable() )
			{
				found.add( findAll( predicate, child ) );
			}
		}

		return concat( found.build() );
	}

	private final ScreenController controller;
	private final Set<MouseButton> pressedButtons = new HashSet<>();
	private final Set<KeyCode> pressedKeys = new HashSet<>();

	public GuiTest()
	{
		this.controller = new FXScreenController();
	}

    /**
     * Same as Thread.sleep(), but without checked exceptions.
     *
     * @param ms time in milliseconds
     */
	public GuiTest sleep( long ms )
	{
		try
		{
			Thread.sleep( ms );
		} catch( InterruptedException e )
		{
			throw new RuntimeException( e );
		}
		return this;
	}

	public GuiTest target( Object window )
	{
		if( window instanceof Window )
		{
			targetWindow( (Window) window );
		} else if( window instanceof String )
		{
			targetWindow( findStageByTitle( (String) window ) );
		} else if( window instanceof Number )
		{
			targetWindow( getWindowByIndex( ((Number) window).intValue() ) );
		} else if( window instanceof Class<?> )
		{
			targetWindow( Iterables.find( getWindows(), Predicates.instanceOf( (Class<?>) window ) ) );
		} else
		{
			Preconditions.checkArgument( false, "Unable to identify Window based on the given argument: %s", window );
		}

		return this;
	}

    /*---------------- Click ----------------*/

    public GuiTest click( MouseButton... buttons )
    {
        if( buttons.length == 0 )
        {
            return click( MouseButton.PRIMARY );
        }

        press( buttons );
        return release( buttons );
    }

    /**
     * Clicks the first Node matching the given query.
     *
     * @param query either CSS selector, label of node or class name.
     * @param buttons
     */
    public GuiTest click( String query, MouseButton... buttons )
    {
        move( query );
        return click( buttons );
    }

    public GuiTest click( Node node, MouseButton... buttons )
    {
        move( node );
        return click( buttons );
    }

    public GuiTest click( Point2D point, MouseButton... buttons )
    {
        move( point );
        return click( buttons );
    }

    public GuiTest click( Bounds bounds, MouseButton... buttons )
    {
        move( bounds );
        return click( buttons );
    }

    public GuiTest click( Scene scene, MouseButton... buttons )
    {
        move( scene );
        return click( buttons );
    }

    public GuiTest click( Window window, MouseButton... buttons )
    {
        move( window );
        return click( buttons );
    }

    public GuiTest click( Matcher<Node> matcher, MouseButton... buttons )
    {
        move( matcher );
        return click( buttons );
    }

    public GuiTest click( Iterable<?> iterable, MouseButton... buttons )
    {
        move( iterable );
        return click( buttons );
    }

    public GuiTest click( OffsetTarget target, MouseButton... buttons )
    {
        move( target );
        return click( buttons );
    }

	public GuiTest rightClick()
	{
		return click( MouseButton.SECONDARY );
	}


    /*---------------- Right-click ----------------*/

    /**
     * Right-clicks a given target.
     */
	public GuiTest rightClick(String query)
	{
		return click( query, MouseButton.SECONDARY );
	}

    public GuiTest rightClick(Node node)
    {
        return click( node, MouseButton.SECONDARY );
    }

    public GuiTest rightClick(Matcher<Node> matcher)
    {
        return click( matcher, MouseButton.SECONDARY );
    }

    public GuiTest rightClick(Scene scene)
    {
        return click( scene, MouseButton.SECONDARY );
    }

    public GuiTest rightClick(Window window)
    {
        return click( window, MouseButton.SECONDARY );
    }

    public GuiTest rightClick(Point2D point)
    {
        return click( point, MouseButton.SECONDARY );
    }

    public GuiTest rightClick(Bounds bounds)
    {
        return click( bounds, MouseButton.SECONDARY );
    }

    public GuiTest rightClick(OffsetTarget target)
    {
        return click( target, MouseButton.SECONDARY );
    }

    public GuiTest rightClick(Iterable<?> iterable)
    {
        return click( iterable, MouseButton.SECONDARY );
    }

    /*---------------- Double-click ----------------*/

    public GuiTest doubleClick()
    {
        return click().click().sleep( 50 );
    }

    /**
     * Double-clicks a given target.
     *
     * @param query
     */
    public GuiTest doubleClick( String query )
    {
        return click( query ).click().sleep( 50 );
    }

	public GuiTest doubleClick( Node node )
	{
		return click( node ).click().sleep( 50 );
	}

    public GuiTest doubleClick( Matcher<Node> matcher )
    {
        return click( matcher ).click().sleep( 50 );
    }

    public GuiTest doubleClick( Scene scene )
    {
        return click( scene ).click().sleep( 50 );
    }

    public GuiTest doubleClick( Window window)
    {
        return click( window ).click().sleep( 50 );
    }

    public GuiTest doubleClick( Point2D point )
    {
        return click( point ).click().sleep( 50 );
    }

    public GuiTest doubleClick( Bounds bounds )
    {
        return click( bounds ).click( ).sleep( 50 );
    }

    public GuiTest doubleClick( OffsetTarget target )
    {
        return click( target ).click( ).sleep( 50 );
    }

    public GuiTest doubleClick( Iterable<?> iterable)
    {
        return click( iterable ).click( ).sleep( 50 );
    }

 	 public GuiTest doubleClick( MouseButton button )
	 {
		  return click( button ).click( ).sleep( 50 );
	 }


    /*----------------  ----------------*/


	public GuiTest eraseCharacters( int characters )
	{
		for( int i = 0; i < characters; i++ )
		{
			type( KeyCode.BACK_SPACE );
		}
		return this;
	}



	public MouseMotion drag( Object source, MouseButton... buttons )
	{
		move( source );
		press( buttons );

		return new MouseMotion( this, buttons );
	}

    /**
     * Moves the mouse cursor to the given coordinates.
     *
     * @param x
     * @param y
     */
	public GuiTest move( double x, double y )
	{
		controller.move( x, y );
		return this;
	}

	public GuiTest move( Object target )
	{
		Point2D point = pointFor( target );
		//Since moving takes time, only do it if we're not already at the desired point.
		if( !controller.getMouse().equals( point ) )
		{
			move( point.getX(), point.getY() );
		}
		//If the target has moved while we were moving the mouse, update to the new position:
		point = pointFor( target );
		controller.position( point.getX(), point.getY() );
		return this;
	}

    /**
     * Moves the mouse cursor relatively to its current position.
     *
     * @param x
     * @param y
     */
	public GuiTest moveBy( double x, double y )
	{
		Point2D mouse = controller.getMouse();
		controller.move( mouse.getX() + x, mouse.getY() + y );
		return this;
	}

    /**
     * Presses and holds a mouse button, until explicitly released.
     *
     * @param buttons defaults to the primary mouse button.
     */
	public GuiTest press( MouseButton... buttons )
	{
		if( buttons.length == 0 )
		{
			return press( MouseButton.PRIMARY );
		}

		for( MouseButton button : buttons )
		{
			if( pressedButtons.add( button ) )
			{
				controller.press( button );
			}
		}
		return this;
	}

    /**
     * Releases a pressed mouse button.
     *
     * @param buttons defaults to the primary mouse button.
     */
	public GuiTest release( MouseButton... buttons )
	{
		if( buttons.length == 0 )
		{
			for( MouseButton button : pressedButtons )
			{
				controller.release( button );
			}
			pressedButtons.clear();
		} else
		{
			for( MouseButton button : buttons )
			{
				if( pressedButtons.remove( button ) )
				{
					controller.release( button );
				}
			}
		}
		return this;
	}


    /*---------------- Scroll ----------------*/

	@Deprecated
	public GuiTest scroll( int amount )
	{
		for( int x = 0; x < Math.abs( amount ); x++ )
		{
			controller.scroll( Integer.signum( amount ) );
		}
		return this;
	}

    /**
     * Scrolls the mouse-wheel a given number of notches in a direction.
     *
     * @param amount the number of notches to scroll
     * @param direction
     * @return
     */
	public GuiTest scroll( int amount, VerticalDirection direction )
	{
		for( int x = 0; x < Math.abs( amount ); x++ )
		{
			controller.scroll( directionToInteger( direction ) );
		}
		return this;
	}

    /**
     * Scrolls the mouse-wheel one notch in the given direction.
     *
     * @param direction
     * @return
     */
	public GuiTest scroll( VerticalDirection direction )
	{
		return scroll( 1, direction );
	}

	private int directionToInteger( VerticalDirection direction )
	{
		if( direction == VerticalDirection.UP )
			return -1;
		return 1;
	}


    /*---------------- Type ----------------*/

    /**
     * Types the given text on the keyboard.
     *
     * Note: Typing depends on the operating system keyboard layout!
     *
     * @param text
     */
	public GuiTest type( String text )
	{
		for( int i = 0; i < text.length(); i++ )
		{
			type( text.charAt( i ) );
			try
			{
				Thread.sleep( 25 );
			} catch( InterruptedException e )
			{
			}
		}
		return this;
	}

    public GuiTest type( char character )
	{
		KeyCode keyCode = KeyCodeUtils.findKeyCode(character);

		if( !Character.isUpperCase( character ) )
		{
			return type( keyCode );
		} else
		{
			KeyCode[] modifiers = new KeyCode[]{KeyCode.SHIFT};
			press( modifiers );
			type( keyCode );
			return release( modifiers );
		}
	}

    /**
     * Alias for type( ).
     *
     * @param keys
     */
	public GuiTest push( KeyCode... keys )
	{
		return type( keys );
	}

    /**
     * Alias for type().
     * @param character
     */
	public GuiTest push( char character )
	{
		return type( character );
	}

	public GuiTest type( KeyCode... keys )
	{
		press( keys );
		return release( keys );
	}

    /**
     * Presses and holds a given key, until explicitly released.
     *
     * @param keys
     */
	public GuiTest press( KeyCode... keys )
	{
		for( KeyCode key : keys )
		{
			if( pressedKeys.add( key ) )
			{
				controller.press( key );
			}
		}
		return this;
	}

    /**
     * Releases a given key.
     *
     * @param keys
     */
	public GuiTest release( KeyCode... keys )
	{
		if( keys.length == 0 )
		{
			for( KeyCode button : pressedKeys )
			{
				controller.release( button );
			}
			pressedKeys.clear();
		} else
		{
			for( KeyCode key : keys )
			{
				if( pressedKeys.remove( key ) )
				{
					controller.release( key );
				}
			}
		}
		return this;
	}

	private Pos nodePosition = Pos.CENTER;

	public GuiTest pos( Pos pos )
	{
		nodePosition = pos;
		return this;
	}

    /**
     * Closes the front-most window using the Alt+F4 keyboard shortcut.
     *
     * @return
     */
	public GuiTest closeCurrentWindow()
	{
		this.press( KeyCode.ALT ).press( KeyCode.F4 ).release( KeyCode.F4 ).release( KeyCode.ALT );
		return this;
	}

	private Point2D pointForBounds( Bounds bounds )
	{
		double x = 0;
		switch( nodePosition.getHpos() )
		{
			case LEFT:
				x = bounds.getMinX();
				break;
			case CENTER:
				x = (bounds.getMinX() + bounds.getMaxX()) / 2;
				break;
			case RIGHT:
				x = bounds.getMaxX();
				break;
		}

		double y = 0;
		switch( nodePosition.getVpos() )
		{
			case TOP:
				y = bounds.getMinY();
				break;
			case CENTER:
			case BASELINE:
				y = (bounds.getMinY() + bounds.getMaxY()) / 2;
				break;
			case BOTTOM:
				y = bounds.getMaxY();
				break;
		}

		return new Point2D( x, y );
	}

	private static Bounds sceneBoundsToScreenBounds( Bounds sceneBounds, Scene scene )
	{
		Window window = targetWindow( scene.getWindow() );
		return new BoundingBox( window.getX() + scene.getX() + sceneBounds.getMinX(), window.getY() + scene.getY()
				+ sceneBounds.getMinY(), sceneBounds.getWidth(), sceneBounds.getHeight() );
	}

	@SuppressWarnings("unchecked")
	public Point2D pointFor( Object target )
	{
		if( target instanceof Point2D )
		{
			return (Point2D) target;
		} else if( target instanceof Bounds )
		{
			return pointForBounds( (Bounds) target );
		} else if( target instanceof String )
		{
			return pointFor( find( (String) target ) );
		} else if( target instanceof Node )
		{
			Node node = (Node) target;
			return pointFor( sceneBoundsToScreenBounds( node.localToScene( node.getBoundsInLocal() ), node.getScene() ) );
		} else if( target instanceof Scene )
		{
			Scene scene = (Scene) target;
			return pointFor( sceneBoundsToScreenBounds( new BoundingBox( 0, 0, scene.getWidth(), scene.getHeight() ),
					scene ) );
		} else if( target instanceof Window )
		{
			Window window = targetWindow( (Window) target );
			return pointFor( new BoundingBox( window.getX(), window.getY(), window.getWidth(), window.getHeight() ) );
		} else if( target instanceof Matcher )
		{
			return pointFor( find( (Matcher) target ) );
		} else if( target instanceof Iterable<?> )
		{
			return pointFor( Iterables.get( (Iterable<?>) target, 0 ) );
		} else if( target instanceof OffsetTarget )
		{
			OffsetTarget offset = (OffsetTarget) target;
			Pos oldPos = nodePosition;
			Point2D targetPoint = pos( Pos.TOP_LEFT ).pointFor( offset.target );
			pos( oldPos );
			return new Point2D( targetPoint.getX() + offset.offsetX, targetPoint.getY() + offset.offsetY );
		}

		throw new IllegalArgumentException( "Unable to get coordinates for: " + target );
	}

	static class OffsetTarget
	{
		private final Object target;
		private final double offsetX;
		private final double offsetY;

		private OffsetTarget( Object target, double offsetX, double offsetY )
		{
			this.target = target;
			this.offsetX = offsetX;
			this.offsetY = offsetY;
		}
	}

}