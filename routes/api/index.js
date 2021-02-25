const router = require('express').Router();
const incomeRoutes = require('./income');
const billsRoutes = require('./bills');
const userRoutes = require('./user');

router.use('/income', incomeRoutes);
router.use('/bills', billsRoutes);
router.use('/users', userRoutes);

module.exports = router;