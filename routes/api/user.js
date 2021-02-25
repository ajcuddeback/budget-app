const router = require('express').Router();
const { User, Bills, Income } = require('../../models');

router.get('/', (req, res) => {
    User.findAll({
        include: [
            {
                model: Bills,
                attributes: ['id', 'name', 'amount']
            },
            {
                model: Income,
                attributes: ['id', 'name', 'amount']
            }
        ]
    })
        .then(dbUserData => {
            if (!dbUserData) {
                res.status(404).json({ message: 'No user found!' });
                return;
            }

            res.json(dbUserData);
        })
        .catch(err => {
            console.log(err);
            res.status(500).json(err);
        })
});

router.get('/:id', (req, res) => {
    User.findOne({
        where: {
            id: req.params.id
        },
        include: [
            {
                model: Bills,
                attributes: ['id', 'name', 'amount']
            },
            {
                model: Income,
                attributes: ['id', 'name', 'amount']
            }
        ]
    })
        .then(dbUserData => {
            if (!dbUserData) {
                res.status(404).json({ message: 'No user found!' });
                return;
            }

            res.json(dbUserData);
        })
        .catch(err => {
            console.log(err);
            res.status(500).json(err);
        })
});

router.post('/', (req, res) => {
    User.create({
        username: req.body.username,
        first_name: req.body.first_name,
        last_name: req.body.last_name,
        password: req.body.password
    })
        .then(dbUserData => {
            res.json(dbUserData);
        })
        .catch(err => {
            console.log(err);
            res.status(500).json(err);
        })
});