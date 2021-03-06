#!/bin/tcsh

foreach dst (mobile wear)
    echo Destination: $dst
    foreach src (sandwatch_trans.png stopwatch_trans.png sandwatch_selected.png sandwatch_deselected.png stopwatch_selected.png stopwatch_deselected.png) 
	echo -n $src
	convert $src -resize 400x400 ../$dst/src/main/res/drawable/$src:r_400.png
	echo -n .
	convert $src -resize 320x320 ../$dst/src/main/res/drawable/$src:r_preview.png
	echo -n .
	convert $src -resize 72x72 ../$dst/src/main/res/drawable-hdpi/$src:r.png
	echo -n .
	convert $src -resize 48x48 ../$dst/src/main/res/drawable-mdpi/$src:r.png
	echo -n .
	convert $src -resize 96x96 ../$dst/src/main/res/drawable-xhdpi/$src:r.png
	echo -n .
	convert $src -resize 144x144 ../$dst/src/main/res/drawable-xxhdpi/$src:r.png
	echo
    end
end
