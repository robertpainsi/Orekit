/* Copyright 2002-2013 CS Systèmes d'Information
 * Licensed to CS Systèmes d'Information (CS) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * CS licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.orekit.propagation.integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.commons.math3.exception.MathIllegalArgumentException;
import org.apache.commons.math3.exception.MathIllegalStateException;
import org.apache.commons.math3.ode.AbstractIntegrator;
import org.apache.commons.math3.ode.ContinuousOutputModel;
import org.apache.commons.math3.ode.EquationsMapper;
import org.apache.commons.math3.ode.ExpandableStatefulODE;
import org.apache.commons.math3.ode.FirstOrderDifferentialEquations;
import org.apache.commons.math3.ode.SecondaryEquations;
import org.apache.commons.math3.ode.events.EventHandler;
import org.apache.commons.math3.ode.nonstiff.ClassicalRungeKuttaIntegrator;
import org.apache.commons.math3.ode.sampling.StepHandler;
import org.apache.commons.math3.ode.sampling.StepInterpolator;
import org.apache.commons.math3.util.FastMath;
import org.orekit.attitudes.AttitudeProvider;
import org.orekit.errors.OrekitException;
import org.orekit.errors.OrekitExceptionWrapper;
import org.orekit.errors.OrekitMessages;
import org.orekit.errors.PropagationException;
import org.orekit.frames.Frame;
import org.orekit.orbits.Orbit;
import org.orekit.orbits.OrbitType;
import org.orekit.orbits.PositionAngle;
import org.orekit.propagation.AbstractPropagator;
import org.orekit.propagation.BoundedPropagator;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.events.EventDetector;
import org.orekit.propagation.sampling.OrekitStepHandler;
import org.orekit.propagation.sampling.OrekitStepInterpolator;
import org.orekit.time.AbsoluteDate;


/** Common handling of {@link org.orekit.propagation.Propagator Propagator}
 *  methods for both numerical and semi-analytical propagators.
 *  @author Luc Maisonobe
 */
public abstract class AbstractIntegratedPropagator extends AbstractPropagator {

    /** Event detectors not related to force models. */
    private final List<EventDetector> detectors;

    /** Integrator selected by the user for the orbital extrapolation process. */
    private final AbstractIntegrator integrator;

    /** Mode handler. */
    private ModeHandler modeHandler;

    /** Additional equations. */
    private List<AdditionalEquations> additionalEquations;

    /** Counter for differential equations calls. */
    private int calls;

    /** Mapper between raw double components and space flight dynamics objects. */
    private StateMapper stateMapper;

    /** Complete equation to be integrated. */
    private ExpandableStatefulODE mathODE;

    /** Underlying raw rawInterpolator. */
    private StepInterpolator mathInterpolator;

    /** Build a new instance.
     * @param integrator numerical integrator to use for propagation.
     */
    protected AbstractIntegratedPropagator(final AbstractIntegrator integrator) {
        detectors           = new ArrayList<EventDetector>();
        additionalEquations = new ArrayList<AdditionalEquations>();
        this.integrator     = integrator;
    }

    /** Initialize the mapper. */
    protected void initMapper() {
        stateMapper = createMapper(null, Double.NaN, null, null, null, null);
    }

    /**  {@inheritDoc} */
    public void setAttitudeProvider(final AttitudeProvider attitudeProvider) {
        super.setAttitudeProvider(attitudeProvider);
        stateMapper = createMapper(stateMapper.getReferenceDate(), stateMapper.getMu(),
                                   stateMapper.getOrbitType(), stateMapper.getPositionAngleType(),
                                   attitudeProvider, stateMapper.getFrame());
    }

    /** Set propagation orbit type.
     * @param orbitType orbit type to use for propagation
     */
    protected void setOrbitType(final OrbitType orbitType) {
        stateMapper = createMapper(stateMapper.getReferenceDate(), stateMapper.getMu(),
                                   orbitType, stateMapper.getPositionAngleType(),
                                   stateMapper.getAttitudeProvider(), stateMapper.getFrame());
    }

    /** Get propagation parameter type.
     * @return orbit type used for propagation
     */
    protected OrbitType getOrbitType() {
        return stateMapper.getOrbitType();
    }

    /** Set position angle type.
     * <p>
     * The position parameter type is meaningful only if {@link
     * #getOrbitType() propagation orbit type}
     * support it. As an example, it is not meaningful for propagation
     * in {@link OrbitType#CARTESIAN Cartesian} parameters.
     * </p>
     * @param positionAngleType angle type to use for propagation
     */
    protected void setPositionAngleType(final PositionAngle positionAngleType) {
        stateMapper = createMapper(stateMapper.getReferenceDate(), stateMapper.getMu(),
                                   stateMapper.getOrbitType(), positionAngleType,
                                   stateMapper.getAttitudeProvider(), stateMapper.getFrame());
    }

    /** Get propagation parameter type.
     * @return angle type to use for propagation
     */
    protected PositionAngle getPositionAngleType() {
        return stateMapper.getPositionAngleType();
    }

    /** Set the central attraction coefficient &mu;.
     * @param mu central attraction coefficient (m<sup>3</sup>/s<sup>2</sup>)
     */
    public void setMu(final double mu) {
        stateMapper = createMapper(stateMapper.getReferenceDate(), mu,
                                   stateMapper.getOrbitType(), stateMapper.getPositionAngleType(),
                                   stateMapper.getAttitudeProvider(), stateMapper.getFrame());
    }

    /** Get the central attraction coefficient &mu;.
     * @return mu central attraction coefficient (m<sup>3</sup>/s<sup>2</sup>)
     * @see #setMu(double)
     */
    public double getMu() {
        return stateMapper.getMu();
    }

    /** Get the number of calls to the differential equations computation method.
     * <p>The number of calls is reset each time the {@link #propagate(AbsoluteDate)}
     * method is called.</p>
     * @return number of calls to the differential equations computation method
     */
    public int getCalls() {
        return calls;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isAdditionalStateManaged(final String name) {

        // first look at already integrated states
        if (super.isAdditionalStateManaged(name)) {
            return true;
        }

        // then look at states we integrate ourselves
        for (final AdditionalEquations equation : additionalEquations) {
            if (equation.getName().equals(name)) {
                return true;
            }
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override
    public String[] getManagedStates() {
        final String[] alreadyIntegrated = super.getManagedStates();
        final String[] managed = new String[alreadyIntegrated.length + additionalEquations.size()];
        System.arraycopy(alreadyIntegrated, 0, managed, 0, alreadyIntegrated.length);
        for (int i = 0; i < additionalEquations.size(); ++i) {
            managed[i + alreadyIntegrated.length] = additionalEquations.get(i).getName();
        }
        return managed;
    }

    /** Add a set of user-specified equations to be integrated along with the orbit propagation.
     * @param additional additional equations
     * @see #setInitialAdditionalState(String, double[])
     * @exception OrekitException if a set of equations with the same name is already present
     */
    public void addAdditionalEquations(final AdditionalEquations additional)
        throws OrekitException {

        // check if the name is already used
        if (isAdditionalStateManaged(additional.getName())) {
            // this set of equations is already registered, complain
            throw new OrekitException(OrekitMessages.ADDITIONAL_STATE_NAME_ALREADY_IN_USE,
                                      additional.getName());
        }

        // this is really a new set of equations, add it
        additionalEquations.add(additional);

    }

    /** {@inheritDoc} */
    public void addEventDetector(final EventDetector detector) {
        detectors.add(detector);
    }

    /** {@inheritDoc} */
    public Collection<EventDetector> getEventsDetectors() {
        return Collections.unmodifiableCollection(detectors);
    }

    /** {@inheritDoc} */
    public void clearEventsDetectors() {
        detectors.clear();
    }

    /** Set up all user defined event detectors.
     */
    protected void setUpUserEventDetectors() {
        for (final EventDetector detector : detectors) {
            setUpEventDetector(integrator, detector);
        }
    }

    /** Wrap an Orekit event detector and register it to the integrator.
     * @param integ integrator into which event detector should be registered
     * @param detector event detector to wrap
     */
    protected void setUpEventDetector(final AbstractIntegrator integ,
                                      final EventDetector detector) {
        integ.addEventHandler(new AdaptedEventDetector(detector),
                              detector.getMaxCheckInterval(),
                              detector.getThreshold(),
                              detector.getMaxIterationCount());
    }

    /** {@inheritDoc}
     * <p>Note that this method has the side effect of replacing the step handlers
     * of the underlying integrator set up in the {@link
     * #AbstractIntegratedPropagator(AbstractIntegrator) constructor}. So if a specific
     * step handler is needed, it should be added after this method has been callled.</p>
     */
    public void setSlaveMode() {
        super.setSlaveMode();
        if (integrator != null) {
            integrator.clearStepHandlers();
        }
        modeHandler = null;
    }

    /** {@inheritDoc}
     * <p>Note that this method has the side effect of replacing the step handlers
     * of the underlying integrator set up in the {@link
     * #AbstractIntegratedPropagator(AbstractIntegrator) constructor}. So if a specific
     * step handler is needed, it should be added after this method has been callled.</p>
     */
    public void setMasterMode(final OrekitStepHandler handler) {
        super.setMasterMode(handler);
        integrator.clearStepHandlers();
        final AdaptedStepHandler wrapped = new AdaptedStepHandler(handler);
        integrator.addStepHandler(wrapped);
        modeHandler = wrapped;
    }

    /** {@inheritDoc}
     * <p>Note that this method has the side effect of replacing the step handlers
     * of the underlying integrator set up in the {@link
     * #AbstractIntegratedPropagator(AbstractIntegrator) constructor}. So if a specific
     * step handler is needed, it should be added after this method has been called.</p>
     */
    public void setEphemerisMode() {
        super.setEphemerisMode();
        integrator.clearStepHandlers();
        final EphemerisModeHandler ephemeris = new EphemerisModeHandler();
        modeHandler = ephemeris;
        integrator.addStepHandler(ephemeris);
    }

    /** {@inheritDoc} */
    public BoundedPropagator getGeneratedEphemeris()
        throws IllegalStateException {
        if (getMode() != EPHEMERIS_GENERATION_MODE) {
            throw OrekitException.createIllegalStateException(OrekitMessages.PROPAGATOR_NOT_IN_EPHEMERIS_GENERATION_MODE);
        }
        return ((EphemerisModeHandler) modeHandler).getEphemeris();
    }

    /** Create a mapper between raw double components and spacecraft state.
    /** Simple constructor.
     * <p>
     * The position parameter type is meaningful only if {@link
     * #getOrbitType() propagation orbit type}
     * support it. As an example, it is not meaningful for propagation
     * in {@link OrbitType#CARTESIAN Cartesian} parameters.
     * </p>
     * @param referenceDate reference date
     * @param mu central attraction coefficient (m<sup>3</sup>/s<sup>2</sup>)
     * @param orbitType orbit type to use for mapping
     * @param positionAngleType angle type to use for propagation
     * @param attitudeProvider attitude provider
     * @param frame inertial frame
     * @return new mapper
     */
    protected abstract StateMapper createMapper(final AbsoluteDate referenceDate, final double mu,
                                                final OrbitType orbitType, final PositionAngle positionAngleType,
                                                final AttitudeProvider attitudeProvider, final Frame frame);

    /** Get the differential equations to integrate (for main state only).
     * @param integ numerical integrator to use for propagation.
     * @return differential equations for main state
     */
    protected abstract MainStateEquations getMainStateEquations(final AbstractIntegrator integ);

    /** {@inheritDoc} */
    public SpacecraftState propagate(final AbsoluteDate target) throws PropagationException {
        try {
            if (getStartDate() == null) {
                if (getInitialState() == null) {
                    throw new PropagationException(OrekitMessages.INITIAL_STATE_NOT_SPECIFIED_FOR_ORBIT_PROPAGATION);
                }
                setStartDate(getInitialState().getDate());
            }
            return propagate(getStartDate(), target);
        } catch (OrekitException oe) {

            // recover a possible embedded PropagationException
            for (Throwable t = oe; t != null; t = t.getCause()) {
                if (t instanceof PropagationException) {
                    throw (PropagationException) t;
                }
            }
            throw new PropagationException(oe);

        }
    }

    /** {@inheritDoc} */
    public SpacecraftState propagate(final AbsoluteDate tStart, final AbsoluteDate tEnd)
        throws PropagationException {
        try {

            if (getInitialState() == null) {
                throw new PropagationException(OrekitMessages.INITIAL_STATE_NOT_SPECIFIED_FOR_ORBIT_PROPAGATION);
            }

            if (!tStart.equals(getInitialState().getDate())) {
                // if propagation start date is not initial date,
                // propagate from initial to start date without event detection
                propagate(tStart, false);
            }

            // propagate from start date to end date with event detection
            return propagate(tEnd, true);

        } catch (OrekitException oe) {

            // recover a possible embedded PropagationException
            for (Throwable t = oe; t != null; t = t.getCause()) {
                if (t instanceof PropagationException) {
                    throw (PropagationException) t;
                }
            }
            throw new PropagationException(oe);

        }
    }

    /** Propagation with or without event detection.
     * @param tEnd target date to which orbit should be propagated
     * @param activateHandlers if true, step and event handlers should be activated
     * @return state at end of propagation
     * @exception PropagationException if orbit cannot be propagated
     */
    protected SpacecraftState propagate(final AbsoluteDate tEnd, final boolean activateHandlers)
        throws PropagationException {
        try {

            if (getInitialState().getDate().equals(tEnd)) {
                // don't extrapolate
                return getInitialState();
            }

            // space dynamics view
            stateMapper = createMapper(getInitialState().getDate(), stateMapper.getMu(),
                                       stateMapper.getOrbitType(), stateMapper.getPositionAngleType(),
                                       stateMapper.getAttitudeProvider(), getInitialState().getFrame());


            // set propagation orbit type
            final Orbit initialOrbit = stateMapper.getOrbitType().convertType(getInitialState().getOrbit());
            if (Double.isNaN(getMu())) {
                setMu(initialOrbit.getMu());
            }

            if (getInitialState().getMass() <= 0.0) {
                throw new PropagationException(OrekitMessages.SPACECRAFT_MASS_BECOMES_NEGATIVE,
                                               getInitialState().getMass());
            }

            integrator.clearEventHandlers();

            // set up events added by user
            setUpUserEventDetectors();

            // convert space flight dynamics API to math API
            mathODE = createODE(integrator);
            mathInterpolator = null;

            // initialize mode handler
            if (modeHandler != null) {
                modeHandler.initialize(activateHandlers);
            }

            // mathematical integration
            try {
                beforeIntegration(getInitialState(), tEnd);
                integrator.integrate(mathODE, tEnd.durationFrom(getInitialState().getDate()));
                afterIntegration();
            } catch (OrekitExceptionWrapper oew) {
                throw oew.getException();
            }

            // get final state
            SpacecraftState finalState =
                    stateMapper.mapArrayToState(mathODE.getTime(), mathODE.getPrimaryState());
            finalState = updateAdditionalStates(finalState);
            for (int i = 0; i < additionalEquations.size(); ++i) {
                final double[] secondary = mathODE.getSecondaryState(i);
                finalState = finalState.addAdditionalState(additionalEquations.get(i).getName(),
                                                           secondary);
            }
            resetInitialState(finalState);
            setStartDate(finalState.getDate());

            return finalState;

        } catch (PropagationException pe) {
            throw pe;
        } catch (OrekitException oe) {
            throw new PropagationException(oe);
        } catch (MathIllegalArgumentException miae) {
            throw PropagationException.unwrap(miae);
        } catch (MathIllegalStateException mise) {
            throw PropagationException.unwrap(mise);
        }
    }

    /** Create an ODE with all equations.
     * @param integ numerical integrator to use for propagation.
     * @return a new ode
     * @exception OrekitException if initial state cannot be mapped
     */
    private ExpandableStatefulODE createODE(final AbstractIntegrator integ)
        throws OrekitException {

        // retrieve initial state
        final double[] initialStateVector  = new double[getBasicDimension()];
        stateMapper.mapStateToArray(getInitialState(), initialStateVector);

        // main part of the ODE
        final ExpandableStatefulODE ode =
                new ExpandableStatefulODE(new ConvertedMainStateEquations(getMainStateEquations(integ)));
        ode.setTime(0.0);
        ode.setPrimaryState(initialStateVector);

        // secondary part of the ODE
        for (int i = 0; i < additionalEquations.size(); ++i) {
            final AdditionalEquations additional = additionalEquations.get(i);
            final double[] data = getInitialState().getAdditionalState(additional.getName());
            final SecondaryEquations secondary =
                    new ConvertedSecondaryStateEquations(additional, data.length);
            ode.addSecondaryEquations(secondary);
            ode.setSecondaryState(i, data);
        }

        return ode;

    }

    /** Method called just before integration.
     * <p>
     * The default implementation does nothing, it may be specialized in subclasses.
     * </p>
     * @param initialState initial state
     * @param tEnd target date at which state should be propagated
     * @exception OrekitException if hook cannot be run
     */
    protected void beforeIntegration(final SpacecraftState initialState,
                                     final AbsoluteDate tEnd)
        throws OrekitException {
        // do nothing by default
    }

    /** Method called just after integration.
     * <p>
     * The default implementation does nothing, it may be specialized in subclasses.
     * </p>
     * @exception OrekitException if hook cannot be run
     */
    protected void afterIntegration()
        throws OrekitException {
        // do nothing by default
    }

    /** Get state vector dimension without additional parameters.
     * @return state vector dimension without additional parameters.
     */
    public int getBasicDimension() {
        return 7;

    }

    /** Get a complete state with all additional equations.
     * @param t current value of the independent <I>time</I> variable
     * @param y array containing the current value of the state vector
     * @return complete state
     * @exception OrekitException if state cannot be mapped
     */
    private SpacecraftState getCompleteState(final double t, final double[] y)
        throws OrekitException {

        // main state
        SpacecraftState state = stateMapper.mapArrayToState(t, y);

        // pre-integrated additional states
        state = updateAdditionalStates(state);

        // additional states integrated here
        if (!additionalEquations.isEmpty()) {

            if (mathODE.getTotalDimension() <= y.length) {
                // the provided y vector already contains everything needed
                final EquationsMapper[] em = mathODE.getSecondaryMappers();
                for (int i = 0; i < additionalEquations.size(); ++i) {
                    final double[] secondary = new double[em[i].getDimension()];
                    System.arraycopy(y, em[i].getFirstIndex(), secondary, 0, secondary.length);
                    state = state.addAdditionalState(additionalEquations.get(i).getName(),
                                                     secondary);
                }
            } else {
                // the y array doesn't contain the additional equations data

                // TODO: remove this case when MATH-965 fix is officially published
                // (i.e for the next Apache Commons Math version after 3.2)
                // The fix for MATH-965 ensures that y always contains all
                // needed data, including additional states, so the workaround
                // below will not be needed anymore

                if (mathInterpolator == null) {
                    // we are still in the first step, before the step handler call
                    // we build a temporary interpolator just for this step
                    final double step = FastMath.abs(integrator.getCurrentSignedStepsize());
                    final ClassicalRungeKuttaIntegrator firstStepIntegrator =
                            new ClassicalRungeKuttaIntegrator(step);
                    firstStepIntegrator.addStepHandler(new StepHandler() {

                        /** {@inheritDoc} */
                        public void init(final double t0, final double[] y0, final double t) {
                        }

                        /** {@inheritDoc} */
                        public void handleStep(final StepInterpolator interpolator, final boolean isLast) {
                            mathInterpolator = interpolator;
                        }

                    });
                    final ExpandableStatefulODE localODE = createODE(firstStepIntegrator);
                    firstStepIntegrator.clearEventHandlers();
                    firstStepIntegrator.integrate(localODE, step);
                }

                // extract the additional data from the spied interpolator
                mathInterpolator.setInterpolatedTime(t);
                for (int i = 0; i < additionalEquations.size(); ++i) {
                    final double[] secondary = mathInterpolator.getInterpolatedSecondaryState(i);
                    state = state.addAdditionalState(additionalEquations.get(i).getName(),
                                                     secondary);
                }

            }

        }

        return state;

    }

    /** Differential equations for the main state (orbit, attitude and mass). */
    public interface MainStateEquations {

        /** Compute differential equations for main state.
         * @param state current state
         * @return derivatives of main state
         * @throws OrekitException if differentials cannot be computed
         */
        double[] computeDerivatives(final SpacecraftState state) throws OrekitException;

    }

    /** Differential equations for the main state (orbit, attitude and mass), with converted API. */
    private class ConvertedMainStateEquations implements FirstOrderDifferentialEquations {

        /** Main state equations. */
        private final MainStateEquations main;

        /** Simple constructor.
         * @param main main state equations
         */
        public ConvertedMainStateEquations(final MainStateEquations main) {
            this.main = main;
            calls = 0;
        }

        /** {@inheritDoc} */
        public int getDimension() {
            return getBasicDimension();
        }

        /** {@inheritDoc} */
        public void computeDerivatives(final double t, final double[] y, final double[] yDot)
            throws OrekitExceptionWrapper {

            try {

                // update space dynamics view
                SpacecraftState currentState = stateMapper.mapArrayToState(t, y);
                currentState = updateAdditionalStates(currentState);

                // compute main state differentials
                final double[] mainDot = main.computeDerivatives(currentState);
                System.arraycopy(mainDot, 0, yDot, 0, mainDot.length);

            } catch (OrekitException oe) {
                throw new OrekitExceptionWrapper(oe);
            }


            // increment calls counter
            ++calls;

        }

    }

    /** Differential equations for the secondary state (Jacobians, user variables ...), with converted API. */
    private class ConvertedSecondaryStateEquations implements SecondaryEquations {

        /** Additional equations. */
        private final AdditionalEquations equations;

        /** Dimension of the additional state. */
        private final int dimension;

        /** Simple constructor.
         * @param equations additional equations
         * @param dimension dimension of the additional state
         */
        public ConvertedSecondaryStateEquations(final AdditionalEquations equations,
                                                final int dimension) {
            this.equations = equations;
            this.dimension = dimension;
        }

        /** {@inheritDoc} */
        public int getDimension() {
            return dimension;
        }

        /** {@inheritDoc} */
        public void computeDerivatives(final double t, final double[] primary,
                                       final double[] primaryDot, final double[] secondary,
                                       final double[] secondaryDot)
            throws OrekitExceptionWrapper {

            try {

                // update space dynamics view
                SpacecraftState currentState = stateMapper.mapArrayToState(t, primary);
                currentState = updateAdditionalStates(currentState);
                currentState = currentState.addAdditionalState(equations.getName(), secondary);

                // compute additional derivatives
                final double[] additionalMainDot =
                        equations.computeDerivatives(currentState, secondaryDot);
                if (additionalMainDot != null) {
                    // the additional equations have an effect on main equations
                    for (int i = 0; i < additionalMainDot.length; ++i) {
                        primaryDot[i] += additionalMainDot[i];
                    }
                }

            } catch (OrekitException oe) {
                throw new OrekitExceptionWrapper(oe);
            }

        }

    }

    /** Adapt an {@link org.orekit.propagation.events.EventDetector}
     * to commons-math {@link org.apache.commons.math3.ode.events.EventHandler} interface.
     * @author Fabien Maussion
     */
    private class AdaptedEventDetector implements EventHandler {

        /** Underlying event detector. */
        private final EventDetector detector;

        /** Build a wrapped event detector.
         * @param detector event detector to wrap
        */
        public AdaptedEventDetector(final EventDetector detector) {
            this.detector = detector;
        }

        /** {@inheritDoc} */
        public void init(final double t0, final double[] y0, final double t) {
            try {

                detector.init(getCompleteState(t0, y0), stateMapper.mapDoubleToDate(t));

            } catch (OrekitException oe) {
                throw new OrekitExceptionWrapper(oe);
            }
        }

        /** {@inheritDoc} */
        public double g(final double t, final double[] y) {
            try {
                return detector.g(getCompleteState(t, y));
            } catch (OrekitException oe) {
                throw new OrekitExceptionWrapper(oe);
            }
        }

        /** {@inheritDoc} */
        public Action eventOccurred(final double t, final double[] y, final boolean increasing) {
            try {

                final SpacecraftState state = getCompleteState(t, y);
                final EventDetector.Action whatNext = detector.eventOccurred(state, increasing);

                switch (whatNext) {
                case STOP :
                    return Action.STOP;
                case RESET_STATE :
                    return Action.RESET_STATE;
                case RESET_DERIVATIVES :
                    return Action.RESET_DERIVATIVES;
                default :
                    return Action.CONTINUE;
                }
            } catch (OrekitException oe) {
                throw new OrekitExceptionWrapper(oe);
            }
        }

        /** {@inheritDoc} */
        public void resetState(final double t, final double[] y) {
            try {
                final SpacecraftState oldState = getCompleteState(t, y);
                final SpacecraftState newState = detector.resetState(oldState);

                // main part
                stateMapper.mapStateToArray(newState, y);

                // secondary part
                final EquationsMapper[] em = mathODE.getSecondaryMappers();
                for (int i = 0; i < additionalEquations.size(); ++i) {
                    final double[] secondary =
                            newState.getAdditionalState(additionalEquations.get(i).getName());
                    System.arraycopy(secondary, 0, y, em[i].getFirstIndex(), secondary.length);
                }

            } catch (OrekitException oe) {
                throw new OrekitExceptionWrapper(oe);
            }
        }

    }

    /** Adapt an {@link org.orekit.propagation.sampling.OrekitStepHandler}
     * to commons-math {@link StepHandler} interface.
     * @author Luc Maisonobe
     */
    private class AdaptedStepHandler
        implements OrekitStepInterpolator, StepHandler, ModeHandler {

        /** Underlying handler. */
        private final OrekitStepHandler handler;

        /** Flag for handler . */
        private boolean activate;

        /** Build an instance.
         * @param handler underlying handler to wrap
         */
        public AdaptedStepHandler(final OrekitStepHandler handler) {
            this.handler = handler;
        }

        /** {@inheritDoc} */
        public void initialize(final boolean activateHandlers) {
            this.activate = activateHandlers;
        }

        /** {@inheritDoc} */
        public void init(final double t0, final double[] y0, final double t) {
            try {
                handler.init(getCompleteState(t0, y0), stateMapper.mapDoubleToDate(t));
            } catch (OrekitException oe) {
                throw new OrekitExceptionWrapper(oe);
            }
        }

        /** {@inheritDoc} */
        public void handleStep(final StepInterpolator interpolator, final boolean isLast) {
            try {
                mathInterpolator = interpolator;
                if (activate) {
                    handler.handleStep(this, isLast);
                }
            } catch (PropagationException pe) {
                throw new OrekitExceptionWrapper(pe);
            }
        }

        /** Get the current grid date.
         * @return current grid date
         */
        public AbsoluteDate getCurrentDate() {
            return stateMapper.mapDoubleToDate(mathInterpolator.getCurrentTime());
        }

        /** Get the previous grid date.
         * @return previous grid date
         */
        public AbsoluteDate getPreviousDate() {
            return stateMapper.mapDoubleToDate(mathInterpolator.getPreviousTime());
        }

        /** Get the interpolated date.
         * <p>If {@link #setInterpolatedDate(AbsoluteDate) setInterpolatedDate}
         * has not been called, the date returned is the same as  {@link
         * #getCurrentDate() getCurrentDate}.</p>
         * @return interpolated date
         * @see #setInterpolatedDate(AbsoluteDate)
         * @see #getInterpolatedState()
         */
        public AbsoluteDate getInterpolatedDate() {
            return stateMapper.mapDoubleToDate(mathInterpolator.getInterpolatedTime());
        }

        /** Set the interpolated date.
         * <p>It is possible to set the interpolation date outside of the current
         * step range, but accuracy will decrease as date is farther.</p>
         * @param date interpolated date to set
         * @see #getInterpolatedDate()
         * @see #getInterpolatedState()
         */
        public void setInterpolatedDate(final AbsoluteDate date) {
            mathInterpolator.setInterpolatedTime(stateMapper.mapDateToDouble(date));
        }

        /** Get the interpolated state.
         * @return interpolated state at the current interpolation date
         * @exception OrekitException if state cannot be interpolated or converted
         * @see #getInterpolatedDate()
         * @see #setInterpolatedDate(AbsoluteDate)
         */
        public SpacecraftState getInterpolatedState() throws OrekitException {
            try {

                SpacecraftState s =
                        stateMapper.mapArrayToState(mathInterpolator.getInterpolatedTime(),
                                                    mathInterpolator.getInterpolatedState());
                s = updateAdditionalStates(s);
                for (int i = 0; i < additionalEquations.size(); ++i) {
                    final double[] secondary = mathInterpolator.getInterpolatedSecondaryState(i);
                    s = s.addAdditionalState(additionalEquations.get(i).getName(), secondary);
                }

                return s;

            } catch (OrekitExceptionWrapper oew) {
                throw oew.getException();
            }
        }

        /** Check is integration direction is forward in date.
         * @return true if integration is forward in date
         */
        public boolean isForward() {
            return mathInterpolator.isForward();
        }

    }

    private class EphemerisModeHandler implements ModeHandler, StepHandler {

        /** Underlying raw mathematical model. */
        private ContinuousOutputModel model;

        /** Generated ephemeris. */
        private BoundedPropagator ephemeris;

        /** Flag for handler . */
        private boolean activate;

        /** Creates a new instance of EphemerisModeHandler which must be
         *  filled by the propagator.
         */
        public EphemerisModeHandler() {
        }

        /** {@inheritDoc} */
        public void initialize(final boolean activateHandlers) {
            this.activate = activateHandlers;
            this.model    = new ContinuousOutputModel();

            // ephemeris will be generated when last step is processed
            this.ephemeris = null;

        }

        /** Get the generated ephemeris.
         * @return a new instance of the generated ephemeris
         */
        public BoundedPropagator getEphemeris() {
            return ephemeris;
        }

        /** {@inheritDoc} */
        public void handleStep(final StepInterpolator interpolator, final boolean isLast)
            throws OrekitExceptionWrapper {
            try {
                if (activate) {
                    model.handleStep(interpolator, isLast);
                    if (isLast) {

                        // set up the boundary dates
                        final double tI = model.getInitialTime();
                        final double tF = model.getFinalTime();
                        final AbsoluteDate startDate = stateMapper.mapDoubleToDate(tI);
                        final AbsoluteDate minDate;
                        final AbsoluteDate maxDate;
                        if (tF < tI) {
                            minDate = stateMapper.mapDoubleToDate(tF);
                            maxDate = startDate;
                        } else {
                            minDate = startDate;
                            maxDate = stateMapper.mapDoubleToDate(tF);
                        }

                        // store the names of additional states
                        final String[] names = new String[additionalEquations.size()];
                        for (int i = 0; i < names.length; ++i) {
                            names[i] = additionalEquations.get(i).getName();
                        }
                        // create the ephemeris
                        // TODO: add the providers for already integrated additional states
                        ephemeris = new IntegratedEphemeris(startDate, minDate, maxDate,
                                                            stateMapper, model,
                                                            getAdditionalStateProviders(), names);

                    }
                }
            } catch (OrekitException oe) {
                throw new OrekitExceptionWrapper(oe);
            }
        }

        /** {@inheritDoc} */
        public void init(final double t0, final double[] y0, final double t) {
            model.init(t0, y0, t);
        }

    }

}
