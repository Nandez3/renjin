#  File src/library/base/R/diag.R
#  Part of the R package, http://www.R-project.org
#
#  This program is free software; you can redistribute it and/or modify
#  it under the terms of the GNU General Public License as published by
#  the Free Software Foundation; either version 2 of the License, or
#  (at your option) any later version.
#
#  This program is distributed in the hope that it will be useful,
#  but WITHOUT ANY WARRANTY; without even the implied warranty of
#  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#  GNU General Public License for more details.
#
#  A copy of the GNU General Public License is available at
#  http://www.r-project.org/Licenses/

diag <- function(x = 1, nrow, ncol)
{
    if (is.matrix(x)) {
        if (nargs() > 1L)
            stop("'nrow' or 'ncol' cannot be specified when 'x' is a matrix")

        if((m <- min(dim(x))) == 0L)
	    return(vector(typeof(x), 0L)) # logical, integer, also list ..

        y <- c(x)[1L + 0L:(m - 1L) * (dim(x)[1L] + 1L)]
        nms <- dimnames(x)
        if (is.list(nms) && !any(sapply(nms, is.null)) &&
            identical((nm <- nms[[1L]][seq_len(m)]), nms[[2L]][seq_len(m)]))
            names(y) <- nm
        return(y)
    }
    if(is.array(x) && length(dim(x)) != 1L)
        stop("'x' is an array, but not 1D.")

    if(missing(x))
	n <- nrow
    else if(length(x) == 1L && nargs() == 1L) {
	n <- as.integer(x)
	x <- 1
    }
    else n <- length(x)
    if(!missing(nrow))
	n <- nrow
    if(missing(ncol))
	ncol <- n
    p <- ncol
    y <- array(0, c(n, p))
    if((m <- min(n, p)) > 0L) y[1L + 0L:(m - 1L) * (n + 1L)] <- x
    y
}

`diag<-` <- function(x, value)
{
    dx <- dim(x)
    if(length(dx) != 2L)
	## no further check, to also work with 'Matrix'
	stop("only matrix diagonals can be replaced")
    len.i <- min(dx)
    i <- seq_len(len.i)
    len.v <- length(value)
    if(len.v != 1L && len.v != len.i)
	stop("replacement diagonal has wrong length")
    if(len.i > 0L) x[cbind(i, i)] <- value
    x
}
