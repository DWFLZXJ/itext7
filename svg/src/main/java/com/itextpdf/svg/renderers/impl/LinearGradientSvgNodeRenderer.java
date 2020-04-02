package com.itextpdf.svg.renderers.impl;

import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.gradients.GradientColorStop.OffsetType;
import com.itextpdf.kernel.geom.AffineTransform;
import com.itextpdf.kernel.geom.Point;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.colors.gradients.GradientColorStop;
import com.itextpdf.kernel.colors.gradients.LinearGradientBuilder;
import com.itextpdf.layout.property.UnitValue;
import com.itextpdf.styledxmlparser.css.util.CssUtils;
import com.itextpdf.svg.SvgConstants.Attributes;
import com.itextpdf.svg.renderers.ISvgNodeRenderer;
import com.itextpdf.svg.renderers.SvgDrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ISvgNodeRenderer} implementation for the &lt;linearGradient&gt; tag.
 */
public class LinearGradientSvgNodeRenderer extends AbstractGradientSvgNodeRenderer {

    @Override
    public Color createColor(SvgDrawContext context, Rectangle objectBoundingBox, float objectBoundingBoxMargin,
            float parentOpacity) {
        if (objectBoundingBox == null) {
            return null;
        }

        LinearGradientBuilder builder = new LinearGradientBuilder();

        for (GradientColorStop stopColor : parseStops(parentOpacity)) {
            builder.addColorStop(stopColor);
        }
        builder.setSpreadMethod(parseSpreadMethod());

        boolean isObjectBoundingBox = isObjectBoundingBoxUnits();

        Point[] coordinates = getCoordinates(context, isObjectBoundingBox);

        builder.setGradientVector(coordinates[0].getX(), coordinates[0].getY(),
                coordinates[1].getX(), coordinates[1].getY());

        AffineTransform gradientTransform = getGradientTransformToUserSpaceOnUse(objectBoundingBox,
                isObjectBoundingBox);

        builder.setCurrentSpaceToGradientVectorSpaceTransformation(gradientTransform);

        return builder.buildColor(
                objectBoundingBox.applyMargins(objectBoundingBoxMargin, objectBoundingBoxMargin, objectBoundingBoxMargin, objectBoundingBoxMargin, true),
                context.getCurrentCanvasTransform()
        );
    }

    @Override
    public ISvgNodeRenderer createDeepCopy() {
        LinearGradientSvgNodeRenderer copy = new LinearGradientSvgNodeRenderer();
        deepCopyAttributesAndStyles(copy);
        deepCopyChildren(copy);
        return copy;
    }

    // TODO: DEVSIX-4136 opacity is not supported now.
    //  The opacity should be equal to 'parentOpacity * stopRenderer.getStopOpacity() * stopColor[3]'
    private List<GradientColorStop> parseStops(float parentOpacity) {
        List<GradientColorStop> stopsList = new ArrayList<>();
        for (StopSvgNodeRenderer stopRenderer : getChildStopRenderers()) {
            float[] stopColor = stopRenderer.getStopColor();
            double offset = stopRenderer.getOffset();
            stopsList.add(new GradientColorStop(stopColor, offset, OffsetType.RELATIVE));
        }

        if (!stopsList.isEmpty()) {
            GradientColorStop firstStop = stopsList.get(0);
            if (firstStop.getOffset() > 0) {
                stopsList.add(0, new GradientColorStop(firstStop, 0f, OffsetType.RELATIVE));
            }

            GradientColorStop lastStop = stopsList.get(stopsList.size() - 1);
            if (lastStop.getOffset() < 1) {
                stopsList.add(new GradientColorStop(lastStop, 1f, OffsetType.RELATIVE));
            }
        }
        return stopsList;
    }

    private AffineTransform getGradientTransformToUserSpaceOnUse(Rectangle objectBoundingBox,
            boolean isObjectBoundingBox) {
        AffineTransform gradientTransform = new AffineTransform();
        if (isObjectBoundingBox) {
            gradientTransform.translate(objectBoundingBox.getX(), objectBoundingBox.getY());
            // We need to scale with dividing the lengths by 0.75 as further we should
            // concatenate gradient transformation matrix which has no absolute parsing.
            // For example, if gradientTransform is set to translate(1, 1) and gradientUnits
            // is set to "objectBoundingBox" then the gradient should be shifted horizontally
            // and vertically exactly by the size of the element bounding box. So, again,
            // as we parse translate(1, 1) to translation(0.75, 0.75) the bounding box in
            // the gradient vector space should be 0.75x0.75 in order for such translation
            // to shift by the complete size of bounding box.
            gradientTransform.scale(objectBoundingBox.getWidth() / 0.75, objectBoundingBox.getHeight() / 0.75);
        }

        AffineTransform svgGradientTransformation = getGradientTransform();
        if (svgGradientTransformation != null) {
            gradientTransform.concatenate(svgGradientTransformation);
        }
        return gradientTransform;
    }

    private Point[] getCoordinates(SvgDrawContext context, boolean isObjectBoundingBox) {
        Point start;
        Point end;
        if (isObjectBoundingBox) {
            start = new Point(getCoordinateForObjectBoundingBox(Attributes.X1, 0),
                    getCoordinateForObjectBoundingBox(Attributes.Y1, 0));
            end = new Point(getCoordinateForObjectBoundingBox(Attributes.X2, 1),
                    getCoordinateForObjectBoundingBox(Attributes.Y2, 0));
        } else {
            Rectangle currentViewPort = context.getCurrentViewPort();
            double x = currentViewPort.getX();
            double y = currentViewPort.getY();
            double width = currentViewPort.getWidth();
            double height = currentViewPort.getHeight();
            start = new Point(getCoordinateForUserSpaceOnUse(Attributes.X1, x, x, width),
                    getCoordinateForUserSpaceOnUse(Attributes.Y1, y, y, height));
            end = new Point(getCoordinateForUserSpaceOnUse(Attributes.X2, x + width, x, width),
                    getCoordinateForUserSpaceOnUse(Attributes.Y2, y, y, height));
        }

        return new Point[] {start, end};
    }

    private double getCoordinateForObjectBoundingBox(String attributeName, double defaultValue) {
        String attributeValue = getAttribute(attributeName);
        double absoluteValue = defaultValue;
        if (CssUtils.isPercentageValue(attributeValue)) {
           absoluteValue = CssUtils.parseRelativeValue(attributeValue, 1);
        } else if (CssUtils.isNumericValue(attributeValue)
                || CssUtils.isMetricValue(attributeValue)
                || CssUtils.isRelativeValue(attributeValue)) {
            // if there is incorrect value metric, then we do not need to parse the value
            int unitsPosition = CssUtils.determinePositionBetweenValueAndUnit(attributeValue);
            if (unitsPosition > 0) {
                // We want to ignore the unit type. From the svg specification:
                // "the normal of the linear gradient is perpendicular to the gradient vector in
                // object bounding box space (i.e., the abstract coordinate system where (0,0)
                // is at the top/left of the object bounding box and (1,1) is at the bottom/right
                // of the object bounding box)".
                // Different browsers treats this differently. We chose the "Google Chrome" approach
                // which treats the "abstract coordinate system" in the coordinate metric measure,
                // i.e. for value '0.5cm' the top/left of the object bounding box would be (1cm, 1cm),
                // for value '0.5em' the top/left of the object bounding box would be (1em, 1em) and etc.
                // no null pointer should be thrown as determine
                absoluteValue = CssUtils.parseDouble(attributeValue.substring(0, unitsPosition)).doubleValue();
            }
        }

        // need to multiply by 0.75 as further the (top, right) coordinates of the object bbox
        // would be transformed into (0.75, 0.75) point instead of (1, 1). The reason described
        // as a comment inside the method constructing the gradient transformation
        return absoluteValue * 0.75;
    }

    private double getCoordinateForUserSpaceOnUse(String attributeName, double defaultValue,
            double start, double length) {
        String attributeValue = getAttribute(attributeName);
        double absoluteValue;
        // TODO: DEVSIX-4018 em and rem actual values are obtained. Default is in use
        //  do not forget to add the test to cover these values change
        UnitValue unitValue = CssUtils.parseLengthValueToPt(attributeValue, 12, 12);
        if (unitValue == null) {
            absoluteValue = defaultValue;
        } else if (unitValue.getUnitType() == UnitValue.PERCENT) {
            absoluteValue = start + (length * unitValue.getValue() / 100);
        } else {
            absoluteValue = unitValue.getValue();
        }
        return absoluteValue;
    }
}
