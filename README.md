# AND-project-6-go-ubiquitous
My Udacity Android Nanodegree project 6
This project was originally from the Udacity course project.
https://github.com/udacity/Advanced_Android_Development

## Required Behavior
1. App works on both round and square face watches.
2. App displays the current time.
3. App displays the high and low temperatures.
4. App displays a graphic that summarizes the day’s weather (e.g., a sunny image, rainy image, cloudy image, etc.).
5. App conforms to common standards found in the Android Nanodegree General Project Guidelines.

## How to Add API KEY
Modify build.gradle file under the app folder.
```
buildTypes.each {
        it.buildConfigField 'String', 'OPEN_WEATHER_MAP_API_KEY', '\"YOUR_OPEN_WEATHER_API_KEY_HERE\"'
    }
}
```
