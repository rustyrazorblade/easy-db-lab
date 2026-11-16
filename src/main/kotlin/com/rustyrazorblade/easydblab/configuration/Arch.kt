package com.rustyrazorblade.easydblab.configuration

/**
 * CPU architecture enum for AMI and container builds.
 *
 * @property type The architecture string used in packer and AWS APIs
 */
enum class Arch(
    val type: String,
) {
    AMD64("amd64"),
    ARM64("arm64"),
    ;

    companion object {
        /**
         * Maps the EC2 `SupportedArchitectures` values reported by `DescribeInstanceTypes` onto an
         * [Arch]. The `x86_64` value maps to [AMD64] and `arm64` maps to [ARM64].
         *
         * The instance type's architectures MUST resolve to exactly one [Arch]. An empty list, an
         * unrecognized value (e.g. `i386`), or a set that maps to more than one architecture is
         * rejected — the cluster cannot select an AMI for an ambiguous or unknown architecture.
         *
         * @param supportedArchitectures EC2 architecture strings (e.g. `["x86_64"]`)
         * @return the single derived [Arch]
         * @throws IllegalArgumentException if the architectures do not resolve to exactly one [Arch]
         */
        fun fromEc2(supportedArchitectures: List<String>): Arch {
            require(supportedArchitectures.isNotEmpty()) {
                "Instance type reported no supported architectures; cannot derive CPU architecture"
            }

            val mapped =
                supportedArchitectures
                    .map { value ->
                        when (value) {
                            "x86_64" -> AMD64
                            "arm64" -> ARM64
                            else -> throw IllegalArgumentException(
                                "Unsupported CPU architecture '$value'; only x86_64 and arm64 are supported",
                            )
                        }
                    }.toSet()

            require(mapped.size == 1) {
                "Instance type maps to multiple CPU architectures $supportedArchitectures; " +
                    "cannot derive a single architecture"
            }

            return mapped.first()
        }
    }
}
