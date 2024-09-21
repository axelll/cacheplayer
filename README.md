# ExoPlayer With Video Caching Demo

## Overview
This project demonstrates an efficient implementation of video playback in Android using Media3 ExoPlayer with simple caching capabilities. It showcases how to create a robust, performant video player that reduces bandwidth usage and improves user experience through smart caching strategies.

## Features
- Seamless video playback using Media3 ExoPlayer
- Application-level caching for improved performance
- Efficient handling of network and cached resources
- Default PlayerView UI for video playback controls
- Support for various video formats

## Implementation Highlights
- Custom `Application` class for global cache management
- `CacheDataSource.Factory` for integrating caching with ExoPlayer
- `ProgressiveMediaSource` for efficient media loading
- Proper lifecycle management in `VideoPlayerActivity`

## Screenshot of Player
![ExoPlayer Demo](https://androiddd.com/wp-content/uploads/2024/09/exoplayer-demo-2048x955.jpg)

## Demo
Check out the demo video [here](https://youtu.be/CRFD8MyKSBc) to see the player in action.

## Getting Started
1. Clone the repository
2. Open the project in Android Studio
3. Build and run the app on your device or emulator

## Usage
To use this video player in your own project:
1. Copy the `ExoVideoPlayer` class to set up application-level caching
2. Implement the `VideoPlayerActivity` in your app, customizing as needed
3. Ensure you have the necessary Media3 ExoPlayer dependencies in your `build.gradle` file

## Dependencies I've used
- androidx-media3-exoplayer: "1.4.1"
- androidx-media3-ui: "1.4.1"

## Detailed Explanation
For a comprehensive breakdown of the implementation, check out the [full article on Androiddd.com](https://androiddd.com/a-simple-video-player-for-android-using-media3-exoplayer/).

## Troubleshooting
If you encounter any issues or need assistance, please [open an issue](https://github.com/androiddd-com/exo-video-player-demo/issues) in this repository.

## Contributing
Contributions are welcome! Feel free to submit a Pull Request.