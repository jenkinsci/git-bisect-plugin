/*
 * See the documentation for more options:
 * https://github.com/jenkins-infra/pipeline-library/
 */
buildPlugin(
    useContainerAgent: true,
    configurations: [
        // Test the long-term support end of the compatibility
        // spectrum (i.e., the minimum required Jenkins version).
        [ platform: 'linux', jdk: '8' ],

        // Test the common case (i.e., a recent LTS release) on both
        // Linux and Windows.
        [ platform: 'linux', jdk: '17', jenkins: '2.414.3' ],
        [ platform: 'windows', jdk: '17', jenkins: '2.414.3' ],

        // Test the bleeding edge of the compatibility spectrum (i.e.,
        // the latest supported Java runtime).
        [ platform: 'linux', jdk: '17', jenkins: '2.429' ],
])
