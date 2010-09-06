package org.orekit.propagation.events;

import org.apache.commons.math.geometry.Vector3D;
import org.apache.commons.math.util.FastMath;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.orekit.Utils;
import org.orekit.attitudes.BodyCenterPointing;
import org.orekit.bodies.CelestialBodyFactory;
import org.orekit.errors.OrekitException;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.orbits.EquinoctialOrbit;
import org.orekit.orbits.Orbit;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.analytical.KeplerianPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.DateComponents;
import org.orekit.time.TimeComponents;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.PVCoordinates;
import org.orekit.utils.PVCoordinatesProvider;



public class CircularFieldOfViewDetectorTest {

    // Body mu
    private double mu;
    
    // Computation date 
    private AbsoluteDate initDate;
    
    // Orbit 
    private Orbit initialOrbit;

    // Reference frame = ITRF 2005
    private Frame itrf;

    // Earth center pointing attitude law 
    private BodyCenterPointing earthCenterAttitudeLaw;

    @Test
    public void testCircularFielOfView() throws OrekitException {

        // Definition of initial conditions with position and velocity
        //------------------------------------------------------------

        // Extrapolator definition
        KeplerianPropagator propagator = new KeplerianPropagator(initialOrbit, earthCenterAttitudeLaw);

        // Event definition : circular field of view, along X axis, aperture 35°
        final double maxCheck  = 1.;
        final PVCoordinatesProvider sunPV = CelestialBodyFactory.getSun();
        final Vector3D center = Vector3D.PLUS_I;
        final double aperture = FastMath.toRadians(35);

        final EventDetector sunVisi = new CircularSunVisiDetector(maxCheck, sunPV, center, aperture);

        // Add event to be detected
        propagator.addEventDetector(sunVisi);

        // Extrapolate from the initial to the final date
        propagator.propagate(initDate.shiftedBy(6000.));

    }
    
    @Before
    public void setUp() {
        try {

            Utils.setDataRoot("regular-data");            

            // Computation date
            // Satellite position as circular parameters
            mu = 3.9860047e14;

            initDate = new AbsoluteDate(new DateComponents(1969, 8, 28),
                                                     TimeComponents.H00,
                                                     TimeScalesFactory.getUTC());
            
            Vector3D position = new Vector3D(7.0e6, 1.0e6, 4.0e6);
            Vector3D velocity = new Vector3D(-500.0, 8000.0, 1000.0);
            initialOrbit = new EquinoctialOrbit(new PVCoordinates(position, velocity),
                                                      FramesFactory.getEME2000(), initDate, mu);


            // Reference frame = ITRF 2005
            itrf = FramesFactory.getITRF2005(true);

            // Create earth center pointing attitude law */
            earthCenterAttitudeLaw = new BodyCenterPointing(itrf);
            
        } catch (OrekitException oe) {
            Assert.fail(oe.getMessage());
        }
          
    }

   
    /** Finder for visibility event.
     * <p>This class extends the elevation detector modifying the event handler.<p>
     */
    private static class CircularSunVisiDetector extends CircularFieldOfViewDetector {

        /** Serializable UID. */
        private static final long serialVersionUID = 1181779674621070074L;

        public CircularSunVisiDetector(final double maxCheck, 
                               final PVCoordinatesProvider pvTarget, final Vector3D center, final double aperture) {
            super(maxCheck, pvTarget, center, aperture);
        }

        public int eventOccurred(final SpacecraftState s, final boolean increasing)
            throws OrekitException {
            if (increasing) {
                // System.err.println(" Sun visibility starts " + s.getDate());
                AbsoluteDate startVisiDate = new AbsoluteDate(new DateComponents(1969, 8, 28),
                                                            new TimeComponents(0, 11 , 7.820),
                                                            TimeScalesFactory.getUTC());
              Assert.assertTrue(s.getDate().durationFrom(startVisiDate) <= 1);
                return CONTINUE;
            } else {
                // System.err.println(" Sun visibility ends at " + s.getDate());
                AbsoluteDate endVisiDate = new AbsoluteDate(new DateComponents(1969, 8, 28),
                                                            new TimeComponents(0, 25 , 18.224),
                                                            TimeScalesFactory.getUTC());
                Assert.assertTrue(s.getDate().durationFrom(endVisiDate) <= 1);
                return CONTINUE;//STOP;
            }
        }

    }

}
