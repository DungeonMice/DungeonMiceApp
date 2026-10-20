package com.phys.dungeonmiceapp.ui;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

/** A shared, small animated GIF; no media decoder or additional worker is needed. */
public final class LoadingAnimation extends StackPane {
    private static final Image ANIMATION = new Image(LoadingAnimation.class.getResource("/images/dungeon-mouse-loading.gif").toExternalForm());

    public LoadingAnimation(double width) {
        if (ANIMATION.isError()) throw new IllegalStateException("No se pudo cargar la animacion", ANIMATION.getException());
        double height = width * ANIMATION.getHeight() / ANIMATION.getWidth();
        setMinSize(width, height); setPrefSize(width, height); setMaxSize(width, height);
        setMouseTransparent(true); setAccessibleText("Cargando");
        ImageView image = new ImageView(ANIMATION); image.setPreserveRatio(true); image.setSmooth(true);
        image.fitWidthProperty().bind(widthProperty()); image.fitHeightProperty().bind(heightProperty());
        getChildren().add(image);
        Rectangle clip = new Rectangle(); clip.widthProperty().bind(widthProperty()); clip.heightProperty().bind(heightProperty());
        clip.setArcWidth(12); clip.setArcHeight(12); setClip(clip);
    }
}
